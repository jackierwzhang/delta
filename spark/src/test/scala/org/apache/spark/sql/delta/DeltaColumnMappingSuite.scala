/*
 * Copyright (2021) The Delta Lake Project Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.delta

// scalastyle:off import.ordering.noEmptyLine
import java.io.File
import java.nio.file.Files

import scala.collection.JavaConverters._
import scala.collection.mutable

import org.apache.spark.sql.delta.DeltaOperations.ManualUpdate
import org.apache.spark.sql.delta.actions.{Action, AddCDCFile, AddFile, Metadata => MetadataAction, Protocol, SetTransaction}
import org.apache.spark.sql.delta.schema.SchemaMergingUtils
import org.apache.spark.sql.delta.sources.DeltaSQLConf
import org.apache.spark.sql.delta.test.DeltaSQLCommandTest
import org.apache.spark.sql.delta.test.DeltaTestImplicits._
import org.apache.hadoop.fs.Path
import org.apache.parquet.format.converter.ParquetMetadataConverter
import org.apache.parquet.hadoop.ParquetFileReader
import org.scalatest.GivenWhenThen

import org.apache.spark.sql.{DataFrame, QueryTest, Row, SparkSession}
import org.apache.spark.sql.catalyst.TableIdentifier
import org.apache.spark.sql.functions._
import org.apache.spark.sql.internal.SQLConf
import org.apache.spark.sql.test.SharedSparkSession
import org.apache.spark.sql.types._
// scalastyle:on import.ordering.noEmptyLine

trait DeltaColumnMappingSuiteUtils extends SharedSparkSession with DeltaSQLCommandTest {


  protected def supportedModes: Seq[String] = Seq("id", "name")

  protected def colName(name: String) = s"$name with special chars ,;{}()\n\t="

  protected def partitionStmt(partCols: Seq[String]): String = {
    if (partCols.nonEmpty) s"PARTITIONED BY (${partCols.map(name => s"`$name`").mkString(",")})"
    else ""
  }

  protected def propString(props: Map[String, String]) = if (props.isEmpty) ""
    else {
      props
        .map { case (key, value) => s"'$key' = '$value'" }
        .mkString("TBLPROPERTIES (", ",", ")")
    }

  protected def alterTableWithProps(
    tableName: String,
    props: Map[String, String]): Unit =
    spark.sql(
      s"""
         | ALTER TABLE $tableName SET ${propString(props)}
         |""".stripMargin)

  protected def mode(props: Map[String, String]): String =
      props.get(DeltaConfigs.COLUMN_MAPPING_MODE.key).getOrElse("none")

  protected def testColumnMapping(
      testName: String,
      enableSQLConf: Boolean = false,
      modes: Option[Seq[String]] = None)(testCode: String => Unit): Unit = {
    test(testName) {
      modes.getOrElse(supportedModes).foreach { mode => {
        withClue(s"Testing under mode: $mode") {
          if (enableSQLConf) {
            withSQLConf(DeltaConfigs.COLUMN_MAPPING_MODE.defaultTablePropertyKey -> mode) {
              testCode(mode)
            }
          } else {
            testCode(mode)
          }
        }
      }}
    }
  }


}

class DeltaColumnMappingSuite extends QueryTest
  with GivenWhenThen  with DeltaColumnMappingSuiteUtils {

  import testImplicits._

  protected def withId(id: Long): Metadata =
    new MetadataBuilder()
      .putLong(DeltaColumnMapping.COLUMN_MAPPING_METADATA_ID_KEY, id)
      .build()

  protected def withPhysicalName(pname: String) =
    new MetadataBuilder()
      .putString(DeltaColumnMapping.COLUMN_MAPPING_PHYSICAL_NAME_KEY, pname)
      .build()

  protected def withIdAndPhysicalName(id: Long, pname: String): Metadata =
    new MetadataBuilder()
      .putLong(DeltaColumnMapping.COLUMN_MAPPING_METADATA_ID_KEY, id)
      .putString(DeltaColumnMapping.COLUMN_MAPPING_PHYSICAL_NAME_KEY, pname)
      .build()

  protected def assertEqual(
      actual: StructType,
      expected: StructType,
      ignorePhysicalName: Boolean = true): Unit = {

    var actualSchema = actual
    var expectedSchema = expected

    val fieldsToRemove = mutable.Set[String]()
    if (ignorePhysicalName) {
      fieldsToRemove.add(DeltaColumnMapping.COLUMN_MAPPING_PHYSICAL_NAME_KEY)
    }

    def removeFields(metadata: Metadata): Metadata = {
      val metadataBuilder = new MetadataBuilder().withMetadata(metadata)
      fieldsToRemove.foreach { field => {
          if (metadata.contains(field)) {
            metadataBuilder.remove(field)
          }
        }
      }
      metadataBuilder.build()
    }

    // drop fields if needed
    actualSchema = SchemaMergingUtils.transformColumns(actual) { (_, field, _) =>
      field.copy(metadata = removeFields(field.metadata))
    }
    expectedSchema = SchemaMergingUtils.transformColumns(expected) { (_, field, _) =>
      field.copy(metadata = removeFields(field.metadata))
    }

    assert(expectedSchema === actualSchema,
      s"""
         |Schema mismatch:
         |
         |expected:
         |${expectedSchema.prettyJson}
         |
         |actual:
         |${actualSchema.prettyJson}
         |""".stripMargin)

  }

  protected def checkSchema(
      tableName: String,
      expectedSchema: StructType,
      ignorePhysicalName: Boolean = true): Unit = {

    // snapshot schema should have all the expected metadata
    val deltaLog = DeltaLog.forTable(spark, TableIdentifier(tableName))
    assertEqual(deltaLog.update().schema, expectedSchema, ignorePhysicalName)

    // table schema should not have any metadata
    assert(spark.table(tableName).schema ===
      DeltaColumnMapping.dropColumnMappingMetadata(expectedSchema))
  }

  // NOTE:
  // All attached metadata to the following sample inputs, if used in source dataframe,
  // will be CLEARED out after metadata is imported into the target table
  // See ImplicitMetadataOperation.updateMetadata() for how the old metadata is cleared
  protected val schema = new StructType()
    .add("a", StringType, true)
    .add("b", IntegerType, true)

  protected val schemaNested = new StructType()
    .add("a", StringType, true)
    .add("b",
      new StructType()
        .add("c", StringType, true)
        .add("d", IntegerType, true),
      true
    )

  protected val schemaWithId = new StructType()
    .add("a", StringType, true, withId(1))
    .add("b", IntegerType, true, withId(2))

  protected val schemaWithIdRandom = new StructType()
    .add("a", StringType, true, withId(111))
    .add("b", IntegerType, true, withId(222))

  protected val schemaWithIdAndPhysicalNameRandom = new StructType()
    .add("a", StringType, true, withIdAndPhysicalName(111, "asjdklsajdkl"))
    .add("b", IntegerType, true, withIdAndPhysicalName(222, "iotiyoiopio"))

  protected val schemaWithDuplicatingIds = new StructType()
    .add("a", StringType, true, withId(1))
    .add("b", IntegerType, true, withId(2))
    .add("c", IntegerType, true, withId(2))

  protected val schemaWithIdAndDuplicatingPhysicalNames = new StructType()
    .add("a", StringType, true, withIdAndPhysicalName(1, "aaa"))
    .add("b", IntegerType, true, withIdAndPhysicalName(2, "bbb"))
    .add("c", IntegerType, true, withIdAndPhysicalName(3, "bbb"))

  protected val schemaWithDuplicatingPhysicalNames = new StructType()
    .add("a", StringType, true, withPhysicalName("aaa"))
    .add("b", IntegerType, true, withPhysicalName("bbb"))
    .add("c", IntegerType, true, withPhysicalName("bbb"))

  protected val schemaWithDuplicatingPhysicalNamesNested = new StructType()
    .add("b",
      new StructType()
        .add("c", StringType, true, withPhysicalName("dupName"))
        .add("d", IntegerType, true, withPhysicalName("dupName")),
      true,
      withPhysicalName("b")
    )

  protected val schemaWithIdNested = new StructType()
    .add("a", StringType, true, withId(1))
    .add("b",
      new StructType()
        .add("c", StringType, true, withId(3))
        .add("d", IntegerType, true, withId(4)),
      true,
      withId(2)
    )

  protected val schemaWithPhysicalNamesNested = new StructType()
    .add("a", StringType, true, withIdAndPhysicalName(1, "aaa"))
    .add("b",
      // let's call this nested struct 'X'.
      new StructType()
        .add("c", StringType, true, withIdAndPhysicalName(2, "ccc"))
        .add("d", IntegerType, true, withIdAndPhysicalName(3, "ddd"))
        .add("foo.bar",
          new StructType().add("f", LongType, true, withIdAndPhysicalName(4, "fff")),
          true,
          withIdAndPhysicalName(5, "foo.foo.foo.bar.bar.bar")),
      true,
      withIdAndPhysicalName(6, "bbb")
    )
    .add("g",
      // nested struct 'X' (see above) is repeated here.
      new StructType()
        .add("c", StringType, true, withIdAndPhysicalName(7, "ccc"))
        .add("d", IntegerType, true, withIdAndPhysicalName(8, "ddd"))
        .add("foo.bar",
          new StructType().add("f", LongType, true, withIdAndPhysicalName(9, "fff")),
          true,
          withIdAndPhysicalName(10, "foo.foo.foo.bar.bar.bar")),
      true,
      withIdAndPhysicalName(11, "ggg")
    )
    .add("h", IntegerType, true, withIdAndPhysicalName(12, "hhh"))

  protected val schemaWithIdNestedRandom = new StructType()
    .add("a", StringType, true, withId(111))
    .add("b",
      new StructType()
        .add("c", StringType, true, withId(333))
        .add("d", IntegerType, true, withId(444)),
      true,
      withId(222)
    )

  // This schema has both a.b and a . b as physical path for its columns, we would like to make sure
  // it shouldn't trigger the duplicated physical name check
  protected val schemaWithDottedColumnNames = new StructType()
    .add("a.b", StringType, true, withIdAndPhysicalName(1, "a.b"))
    .add("a", new StructType()
      .add("b", StringType, true, withIdAndPhysicalName(3, "b")),
      true, withIdAndPhysicalName(2, "a"))

  protected def dfWithoutIds(spark: SparkSession) =
    spark.createDataFrame(Seq(Row("str1", 1), Row("str2", 2)).asJava, schema)

  protected def dfWithoutIdsNested(spark: SparkSession) =
    spark.createDataFrame(
      Seq(Row("str1", Row("str1.1", 1)), Row("str2", Row("str1.2", 2))).asJava, schemaNested)

  protected def dfWithIds(spark: SparkSession, randomIds: Boolean = false) =
    spark.createDataFrame(Seq(Row("str1", 1), Row("str2", 2)).asJava,
      if (randomIds) schemaWithIdRandom else schemaWithId)

  protected def dfWithIdsNested(spark: SparkSession, randomIds: Boolean = false) =
    spark.createDataFrame(
      Seq(Row("str1", Row("str1.1", 1)), Row("str2", Row("str1.2", 2))).asJava,
      if (randomIds) schemaWithIdNestedRandom else schemaWithIdNested)

  protected def checkProperties(
      tableName: String,
      mode: Option[String] = None,
      readerVersion: Int = 1,
      writerVersion: Int = 2,
      curMaxId: Long = 0): Unit = {
    val props =
      spark.sql(s"SHOW TBLPROPERTIES $tableName").as[(String, String)].collect().toMap
    assert(props.get("delta.minReaderVersion").map(_.toInt) == Some(readerVersion))
    assert(props.get("delta.minWriterVersion").map(_.toInt) == Some(writerVersion))

    assert(props.get(DeltaConfigs.COLUMN_MAPPING_MODE.key) == mode)
    assert(props.get(DeltaConfigs.COLUMN_MAPPING_MAX_ID.key).map(_.toLong).getOrElse(0) == curMaxId)
  }

  protected def createTableWithDeltaTableAPI(
      tableName: String,
      props: Map[String, String] = Map.empty,
      withColumnIds: Boolean = false,
      isPartitioned: Boolean = false): Unit = {
    val schemaToUse = if (withColumnIds) schemaWithId else schema
    val builder = io.delta.tables.DeltaTable.createOrReplace(spark)
      .tableName(tableName)
      .addColumn(schemaToUse.fields(0))
      .addColumn(schemaToUse.fields(1))
    props.foreach { case (key, value) =>
      builder.property(key, value)
    }
    if (isPartitioned) {
      builder.partitionedBy("a")
    }
    builder.execute()
  }

  protected def createTableWithSQLCreateOrReplaceAPI(
      tableName: String,
      props: Map[String, String] = Map.empty,
      withColumnIds: Boolean = false,
      isPartitioned: Boolean = false,
      nested: Boolean = false,
      randomIds: Boolean = false): Unit = {
    withTable("source") {
      val dfToWrite = if (withColumnIds) {
        if (nested) {
          dfWithIdsNested(spark, randomIds)
        } else {
          dfWithIds(spark, randomIds)
        }
      } else {
        if (nested) {
          dfWithoutIdsNested(spark)
        } else {
          dfWithoutIds(spark)
        }
      }
      dfToWrite.write.saveAsTable("source")
      val partitionStmt = if (isPartitioned) "PARTITIONED BY (a)" else ""
      spark.sql(
        s"""
           |CREATE OR REPLACE TABLE $tableName
           |USING DELTA
           |$partitionStmt
           |${propString(props)}
           |AS SELECT * FROM source
           |""".stripMargin)
    }
  }

  protected def createTableWithSQLAPI(
      tableName: String,
      props: Map[String, String] = Map.empty,
      withColumnIds: Boolean = false,
      isPartitioned: Boolean = false,
      nested: Boolean = false,
      randomIds: Boolean = false): Unit = {
    withTable("source") {
      val dfToWrite = if (withColumnIds) {
        if (nested) {
          dfWithIdsNested(spark, randomIds)
        } else {
          dfWithIds(spark, randomIds)
        }
      } else {
        if (nested) {
          dfWithoutIdsNested(spark)
        } else {
          dfWithoutIds(spark)
        }
      }
      dfToWrite.write.saveAsTable("source")
      val partitionStmt = if (isPartitioned) "PARTITIONED BY (a)" else ""
      spark.sql(
        s"""
           |CREATE TABLE $tableName
           |USING DELTA
           |$partitionStmt
           |${propString(props)}
           |AS SELECT * FROM source
           |""".stripMargin)
    }
  }

  protected def createTableWithDataFrameAPI(
      tableName: String,
      props: Map[String, String] = Map.empty,
      withColumnIds: Boolean = false,
      isPartitioned: Boolean = false,
      nested: Boolean = false,
      randomIds: Boolean = false): Unit = {
    val sqlConfs = props.map { case (key, value) =>
      "spark.databricks.delta.properties.defaults." + key.stripPrefix("delta.") -> value
    }
    withSQLConf(sqlConfs.toList: _*) {
      val dfToWrite = if (withColumnIds) {
        if (nested) {
          dfWithIdsNested(spark, randomIds)
        } else {
          dfWithIds(spark, randomIds)
        }
      } else {
        if (nested) {
          dfWithoutIdsNested(spark)
        } else {
          dfWithoutIds(spark)
        }
      }
      if (isPartitioned) {
        dfToWrite.write.format("delta").partitionBy("a").saveAsTable(tableName)
      } else {
        dfToWrite.write.format("delta").saveAsTable(tableName)
      }
    }
  }

  protected def createTableWithDataFrameWriterV2API(
      tableName: String,
      props: Map[String, String] = Map.empty,
      withColumnIds: Boolean = false,
      isPartitioned: Boolean = false,
      nested: Boolean = false,
      randomIds: Boolean = false): Unit = {
    val dfToWrite = if (withColumnIds) {
      if (nested) {
        dfWithIdsNested(spark, randomIds)
      } else {
        dfWithIds(spark, randomIds)
      }
    } else {
      if (nested) {
        dfWithoutIdsNested(spark)
      } else {
        dfWithoutIds(spark)
      }
    }
    val writer = dfToWrite.writeTo(tableName).using("delta")
    props.foreach(prop => writer.tableProperty(prop._1, prop._2))
    if (isPartitioned) writer.partitionedBy('a)
    writer.create()
  }

  protected def createStrictSchemaTableWithDeltaTableApi(
      tableName: String,
      schema: StructType,
      props: Map[String, String] = Map.empty,
      isPartitioned: Boolean = false): Unit = {
    val builder = io.delta.tables.DeltaTable.createOrReplace(spark)
      .tableName(tableName)
    builder.addColumns(schema)
    props.foreach(prop => builder.property(prop._1, prop._2))
    if (isPartitioned) builder.partitionedBy("a")
    builder.execute()
  }

  protected def testCreateTableColumnMappingMode(
      tableName: String,
      expectedSchema: StructType,
      ignorePhysicalName: Boolean,
      mode: String,
      createNewTable: Boolean = true)(fn: => Unit): Unit = {
    withTable(tableName) {
        fn
      checkProperties(tableName,
        readerVersion = 2,
        writerVersion = 5,
        mode = Some(mode),
        curMaxId = DeltaColumnMapping.findMaxColumnId(expectedSchema)
      )
      checkSchema(tableName, expectedSchema, ignorePhysicalName)
    }
  }

  test("find max column id in existing columns") {
    assert(DeltaColumnMapping.findMaxColumnId(schemaWithId) == 2)
    assert(DeltaColumnMapping.findMaxColumnId(schemaWithIdNested) == 4)
    assert(DeltaColumnMapping.findMaxColumnId(schemaWithIdRandom) == 222)
    assert(DeltaColumnMapping.findMaxColumnId(schemaWithIdNestedRandom) == 444)
    assert(DeltaColumnMapping.findMaxColumnId(schema) == 0)
    assert(DeltaColumnMapping.findMaxColumnId(new StructType()) == 0)
  }

  test("Enable column mapping with schema change on table with no schema") {
    withTempDir { dir =>
      val tablePath = dir.getCanonicalPath
      Seq((1, "a"), (2, "b")).toDF("id", "name")
        .write.mode("append").format("delta").save(tablePath)
      val deltaLog = DeltaLog.forTable(spark, tablePath)
      val txn = deltaLog.startTransaction()
      txn.commitManually(actions.Metadata()) // Whip the schema out
      val txn2 = deltaLog.startTransaction()
      txn2.commitManually(Protocol(2, 5))
      txn2.updateMetadata(actions.Metadata(
        configuration = Map("delta.columnMapping.mode" -> "name"),
        schemaString = new StructType().add("a", StringType).json))

      // Now ensure that it is not allowed to enable column mapping with schema change
      // on a table with a schema
      Seq((1, "a"), (2, "b")).toDF("id", "name")
        .write.mode("overwrite").format("delta")
        .option("overwriteSchema", "true")
        .save(tablePath)
      val txn3 = deltaLog.startTransaction()
      txn3.commitManually(Protocol(2, 5))
      val e = intercept[DeltaColumnMappingUnsupportedException] {
        txn3.updateMetadata(
          actions.Metadata(
          configuration = Map("delta.columnMapping.mode" -> "name"),
          schemaString = new StructType().add("a", StringType).json))
      }
      val msg = "Schema changes are not allowed during the change of column mapping mode."
      assert(e.getMessage.contains(msg))
    }
  }

  // TODO: repurpose this once we roll out the proper semantics for CM + streaming
  testColumnMapping("isColumnMappingReadCompatible") { mode =>
    // Set up table based on mode and return the initial metadata actions for comparison
    def setupInitialTable(deltaLog: DeltaLog): (MetadataAction, MetadataAction) = {
      val tablePath = deltaLog.dataPath.toString
      if (mode == NameMapping.name) {
        Seq((1, "a"), (2, "b")).toDF("id", "name")
          .write.mode("append").format("delta").save(tablePath)
        // schema: <id, name>
        val m0 = deltaLog.update().metadata

        // add a column
        sql(s"ALTER TABLE delta.`$tablePath` ADD COLUMN (score long)")
        // schema: <id, name, score>
        val m1 = deltaLog.update().metadata

        // column mapping not enabled -> not blocked at all
        assert(DeltaColumnMapping.hasNoColumnMappingSchemaChanges(m1, m0))

        // upgrade to name mode
        alterTableWithProps(s"delta.`$tablePath`", Map(
          DeltaConfigs.COLUMN_MAPPING_MODE.key -> "name",
          DeltaConfigs.MIN_READER_VERSION.key -> "2",
          DeltaConfigs.MIN_WRITER_VERSION.key -> "5"))

        (m0, m1)
      } else {
        // for id mode, just create the table
        withSQLConf(DeltaConfigs.COLUMN_MAPPING_MODE.defaultTablePropertyKey -> "id") {
          Seq((1, "a"), (2, "b")).toDF("id", "name")
            .write.mode("append").format("delta").save(tablePath)
        }
        // schema: <id, name>
        val m0 = deltaLog.update().metadata

        // add a column
        sql(s"ALTER TABLE delta.`$tablePath` ADD COLUMN (score long)")
        // schema: <id, name, score>
        val m1 = deltaLog.update().metadata

        // add column shouldn't block
        assert(DeltaColumnMapping.hasNoColumnMappingSchemaChanges(m1, m0))

        (m0, m1)
      }
    }

    withTempDir { dir =>
      val tablePath = dir.getCanonicalPath
      val deltaLog = DeltaLog.forTable(spark, tablePath)

      val (m0, m1) = setupInitialTable(deltaLog)

      // schema: <id, name, score>
      val m2 = deltaLog.update().metadata

      assert(DeltaColumnMapping.hasNoColumnMappingSchemaChanges(m2, m1))
      assert(DeltaColumnMapping.hasNoColumnMappingSchemaChanges(m2, m0))

      // rename column
      sql(s"ALTER TABLE delta.`$tablePath` RENAME COLUMN score TO age")
      // schema: <id, name, age>
      val m3 = deltaLog.update().metadata

      assert(!DeltaColumnMapping.hasNoColumnMappingSchemaChanges(m3, m2))
      assert(!DeltaColumnMapping.hasNoColumnMappingSchemaChanges(m3, m1))
      // But IS read compatible with the initial schema, because the added column should not
      // be blocked by this column mapping check.
      assert(DeltaColumnMapping.hasNoColumnMappingSchemaChanges(m3, m0))

      // drop a column
      sql(s"ALTER TABLE delta.`$tablePath` DROP COLUMN age")
      // schema: <id, name>
      val m4 = deltaLog.update().metadata

      assert(!DeltaColumnMapping.hasNoColumnMappingSchemaChanges(m4, m3))
      assert(!DeltaColumnMapping.hasNoColumnMappingSchemaChanges(m4, m2))
      assert(!DeltaColumnMapping.hasNoColumnMappingSchemaChanges(m4, m1))
      // but IS read compatible with the initial schema, because the added column is dropped
      assert(DeltaColumnMapping.hasNoColumnMappingSchemaChanges(m4, m0))

      // add back the same column
      sql(s"ALTER TABLE delta.`$tablePath` ADD COLUMN (score long)")
      // schema: <id, name, score>
      val m5 = deltaLog.update().metadata

      // It IS read compatible with the previous schema, because the added column should not
      // blocked by this column mapping check.
      assert(DeltaColumnMapping.hasNoColumnMappingSchemaChanges(m5, m4))
      assert(!DeltaColumnMapping.hasNoColumnMappingSchemaChanges(m5, m3))
      assert(!DeltaColumnMapping.hasNoColumnMappingSchemaChanges(m5, m2))
      // But Since the new added column has a different physical name as all previous columns,
      // even it has the same logical name as say, m1.schema, we will still block
      assert(!DeltaColumnMapping.hasNoColumnMappingSchemaChanges(m5, m1))
      // But it IS read compatible with the initial schema, because the added column should not
      // be blocked by this column mapping check.
      assert(DeltaColumnMapping.hasNoColumnMappingSchemaChanges(m5, m0))
    }
  }

  testColumnMapping("create table through raw schema API should " +
    "auto bump the version and retain input metadata") { mode =>

    // provides id only (let Delta generate physical name for me)
    testCreateTableColumnMappingMode(
      "t1", schemaWithIdRandom, ignorePhysicalName = true, mode = mode) {
      createStrictSchemaTableWithDeltaTableApi(
        "t1",
        schemaWithIdRandom,
        Map(DeltaConfigs.COLUMN_MAPPING_MODE.key -> mode))
    }

    // provides id and physical name (Delta shouldn't rebuild/override)
    // we use random ids as input, which shouldn't be changed too
    testCreateTableColumnMappingMode(
      "t1", schemaWithIdAndPhysicalNameRandom, ignorePhysicalName = false, mode = mode) {
      createStrictSchemaTableWithDeltaTableApi(
        "t1",
        schemaWithIdAndPhysicalNameRandom,
        Map(DeltaConfigs.COLUMN_MAPPING_MODE.key -> mode))
    }

  }

  testColumnMapping("create table through dataframe should " +
    "auto bumps the version and rebuild schema metadata/drop dataframe metadata") { mode =>
    // existing ids should be dropped/ignored and ids should be regenerated
    // so for tests below even if we are ingesting dfs with random ids
    // we should still expect schema with normal sequential ids
    val expectedSchema = schemaWithId

    testCreateTableColumnMappingMode(
      "t1", expectedSchema, ignorePhysicalName = true, mode = mode) {
      createTableWithSQLAPI(
        "t1",
        Map(DeltaConfigs.COLUMN_MAPPING_MODE.key -> mode),
        withColumnIds = true,
        randomIds = true)
    }

    testCreateTableColumnMappingMode(
      "t1", expectedSchema, ignorePhysicalName = true, mode = mode) {
      createTableWithDataFrameAPI(
        "t1",
        Map(DeltaConfigs.COLUMN_MAPPING_MODE.key -> mode),
        withColumnIds = true,
        randomIds = true)
    }

    testCreateTableColumnMappingMode(
      "t1", expectedSchema, ignorePhysicalName = true, mode = mode) {
      createTableWithSQLCreateOrReplaceAPI(
        "t1",
        Map(DeltaConfigs.COLUMN_MAPPING_MODE.key -> mode),
        withColumnIds = true,
        randomIds = true)
    }

    testCreateTableColumnMappingMode(
      "t1", expectedSchema, ignorePhysicalName = true, mode = mode) {
      createTableWithDataFrameWriterV2API(
        "t1",
        Map(DeltaConfigs.COLUMN_MAPPING_MODE.key -> mode),
        withColumnIds = true,
        randomIds = true)
    }
  }

  test("create table with none mode") {
    withTable("t1") {
      // column ids will be dropped, having the options here to make sure such happens
      createTableWithSQLAPI(
        "t1",
        Map(DeltaConfigs.COLUMN_MAPPING_MODE.key -> "none"),
        withColumnIds = true,
        randomIds = true)

      // Should be still on old protocol, the schema shouldn't have any metadata
      checkProperties(
        "t1",
        mode = Some("none"))

      checkSchema("t1", schema, ignorePhysicalName = false)
    }
  }

  testColumnMapping("update column mapped table invalid max id property is blocked") { mode =>
    withTable("t1") {
      createTableWithSQLAPI(
        "t1",
        Map(DeltaConfigs.COLUMN_MAPPING_MODE.key -> mode),
        withColumnIds = true
      )

      val log = DeltaLog.forTable(spark, TableIdentifier("t1"))
      // Get rid of max column id prop
      assert {
        intercept[DeltaAnalysisException] {
          log.withNewTransaction { txn =>
            val existingMetadata = log.update().metadata
            txn.commit(existingMetadata.copy(configuration =
              existingMetadata.configuration - DeltaConfigs.COLUMN_MAPPING_MAX_ID.key) :: Nil,
              DeltaOperations.ManualUpdate)
          }
        }.getErrorClass == "DELTA_COLUMN_MAPPING_MAX_COLUMN_ID_NOT_SET"
      }
      // Use an invalid max column id prop
      assert {
        intercept[DeltaAnalysisException] {
          log.withNewTransaction { txn =>
            val existingMetadata = log.update().metadata
            txn.commit(existingMetadata.copy(configuration =
              existingMetadata.configuration ++ Map(
                // '1' is less than the current max
                DeltaConfigs.COLUMN_MAPPING_MAX_ID.key -> "1"
              )) :: Nil,
              DeltaOperations.ManualUpdate)
          }
        }.getErrorClass == "DELTA_COLUMN_MAPPING_MAX_COLUMN_ID_NOT_SET_CORRECTLY"
      }
    }
  }

  testColumnMapping(
    "create column mapped table with duplicated id/physical name should error"
  ) { mode =>
    withTable("t1") {
      val e = intercept[ColumnMappingException] {
        createStrictSchemaTableWithDeltaTableApi(
          "t1",
          schemaWithDuplicatingIds,
          Map(DeltaConfigs.COLUMN_MAPPING_MODE.key -> mode))
      }
      assert(
        e.getMessage.contains(
          s"Found duplicated column id `2` in column mapping mode `$mode`"))
      assert(e.getMessage.contains(DeltaColumnMapping.COLUMN_MAPPING_METADATA_ID_KEY))

      val e2 = intercept[ColumnMappingException] {
        createStrictSchemaTableWithDeltaTableApi(
          "t1",
          schemaWithIdAndDuplicatingPhysicalNames,
          Map(DeltaConfigs.COLUMN_MAPPING_MODE.key -> mode))
      }
      assert(
        e2.getMessage.contains(
          s"Found duplicated physical name `bbb` in column mapping mode `$mode`"))
      assert(e2.getMessage.contains(DeltaColumnMapping.COLUMN_MAPPING_PHYSICAL_NAME_KEY))
    }

    // for name mode specific, we would also like to check for name duplication
    if (mode == "name") {
      val e = intercept[ColumnMappingException] {
        createStrictSchemaTableWithDeltaTableApi(
          "t1",
          schemaWithDuplicatingPhysicalNames,
          Map(DeltaConfigs.COLUMN_MAPPING_MODE.key -> mode))
      }
      assert(
        e.getMessage.contains(
          s"Found duplicated physical name `bbb` in column mapping mode `$mode`")
      )

      val e2 = intercept[ColumnMappingException] {
        createStrictSchemaTableWithDeltaTableApi(
          "t1",
          schemaWithDuplicatingPhysicalNamesNested,
          Map(DeltaConfigs.COLUMN_MAPPING_MODE.key -> mode))
      }
      assert(
        e2.getMessage.contains(
          s"Found duplicated physical name `b.dupName` in column mapping mode `$mode`")
      )
    }
  }

  testColumnMapping(
    "create table in column mapping mode without defining ids explicitly"
  ) { mode =>
    withTable("t1") {
      // column ids will be dropped, having the options here to make sure such happens
      createTableWithSQLAPI(
        "t1",
        Map(DeltaConfigs.COLUMN_MAPPING_MODE.key -> mode),
        withColumnIds = true,
        randomIds = true)
      checkSchema("t1", schemaWithId)
      checkProperties("t1",
        readerVersion = 2,
        writerVersion = 5,
        mode = Some(mode),
        curMaxId = DeltaColumnMapping.findMaxColumnId(schemaWithId)
      )
    }
  }

  testColumnMapping("alter column order in schema on new protocol") { mode =>
    withTable("t1") {
      // column ids will be dropped, having the options here to make sure such happens
      createTableWithSQLAPI("t1",
        Map(DeltaConfigs.COLUMN_MAPPING_MODE.key -> mode),
        withColumnIds = true,
        nested = true,
        randomIds = true)
      spark.sql(
        """
          |ALTER TABLE t1 ALTER COLUMN a AFTER b
          |""".stripMargin
      )

      checkProperties("t1",
        readerVersion = 2,
        writerVersion = 5,
        mode = Some(mode),
        curMaxId = DeltaColumnMapping.findMaxColumnId(schemaWithIdNested))
      checkSchema(
        "t1",
        schemaWithIdNested.copy(fields = schemaWithIdNested.fields.reverse))
    }
  }

  testColumnMapping("add column in schema on new protocol") { mode =>

    def check(expectedSchema: StructType): Unit = {
      val curMaxId = DeltaColumnMapping.findMaxColumnId(expectedSchema) + 1
      checkSchema("t1", expectedSchema)
      spark.sql(
        """
          |ALTER TABLE t1 ADD COLUMNS (c STRING AFTER b)
          |""".stripMargin
      )

      checkProperties("t1",
        readerVersion = 2,
        writerVersion = 5,
        mode = Some(mode),
        curMaxId = curMaxId)

      checkSchema("t1", expectedSchema.add("c", StringType, true, withId(curMaxId)))

      val curMaxId2 = DeltaColumnMapping.findMaxColumnId(expectedSchema) + 2

      spark.sql(
        """
          |ALTER TABLE t1 ADD COLUMNS (d STRING AFTER c)
          |""".stripMargin
      )
      checkProperties("t1",
        readerVersion = 2,
        writerVersion = 5,
        mode = Some(mode),
        curMaxId = curMaxId2)
      checkSchema("t1",
        expectedSchema
          .add("c", StringType, true, withId(curMaxId))
          .add("d", StringType, true, withId(curMaxId2)))
    }

    withTable("t1") {
      // column ids will be dropped, having the options here to make sure such happens
      createTableWithSQLAPI(
        "t1",
        Map(DeltaConfigs.COLUMN_MAPPING_MODE.key -> mode), withColumnIds = true, randomIds = true)

      check(schemaWithId)
    }

    withTable("t1") {
      // column ids will NOT be dropped, so future ids should update based on the current max
      createStrictSchemaTableWithDeltaTableApi(
        "t1",
        schemaWithIdRandom,
        Map(DeltaConfigs.COLUMN_MAPPING_MODE.key -> mode)
      )

      check(schemaWithIdRandom)
    }
  }

  testColumnMapping("add nested column in schema on new protocol") { mode =>
    withTable("t1") {
      // column ids will be dropped, having the options here to make sure such happens
      createTableWithSQLAPI(
        "t1",
        Map(DeltaConfigs.COLUMN_MAPPING_MODE.key -> mode),
        withColumnIds = true,
        nested = true,
        randomIds = true)

      checkSchema("t1", schemaWithIdNested)

      val curMaxId = DeltaColumnMapping.findMaxColumnId(schemaWithIdNested) + 1

      spark.sql(
        """
          |ALTER TABLE t1 ADD COLUMNS (b.e STRING AFTER d)
          |""".stripMargin
      )

      checkProperties("t1",
        readerVersion = 2,
        writerVersion = 5,
        mode = Some(mode),
        curMaxId = curMaxId)
      checkSchema("t1",
          schemaWithIdNested.merge(
            new StructType().add(
              "b",
              new StructType().add(
                "e", StringType, true, withId(5)),
              true,
              withId(2)
            ))
      )

      val curMaxId2 = DeltaColumnMapping.findMaxColumnId(schemaWithIdNested) + 2
      spark.sql(
        """
          |ALTER TABLE t1 ADD COLUMNS (b.f STRING AFTER e)
          |""".stripMargin
      )
      checkProperties("t1",
        readerVersion = 2,
        writerVersion = 5,
        mode = Some(mode),
        curMaxId = curMaxId2)
      checkSchema("t1",
          schemaWithIdNested.merge(
            new StructType().add(
              "b",
              new StructType().add(
                "e", StringType, true, withId(5)),
              true,
              withId(2)
            )).merge(
          new StructType().add(
              "b",
              new StructType()
                .add("f", StringType, true, withId(6)),
              true,
              withId(2))
        ))

    }
  }

  testColumnMapping("write/merge df to table") { mode =>
    withTable("t1") {
      // column ids will be dropped, having the options here to make sure such happens
      createTableWithDataFrameAPI("t1",
        Map(DeltaConfigs.COLUMN_MAPPING_MODE.key -> mode), withColumnIds = true, randomIds = true)
      val curMaxId = DeltaColumnMapping.findMaxColumnId(schemaWithId)

      val df1 = dfWithIds(spark)
      df1.write
         .format("delta")
         .mode("append")
         .saveAsTable("t1")

      checkProperties("t1",
        readerVersion = 2,
        writerVersion = 5,
        mode = Some(mode),
        curMaxId = curMaxId)
      checkSchema("t1", schemaWithId)

      val previousSchema = spark.table("t1").schema
      // ingest df with random id should not cause existing schema col id to change
      val df2 = dfWithIds(spark, randomIds = true)
      df2.write
         .format("delta")
         .mode("append")
         .saveAsTable("t1")

      checkProperties("t1",
        readerVersion = 2,
        writerVersion = 5,
        mode = Some(mode),
        curMaxId = curMaxId)

      // with checkPhysicalSchema check
      checkSchema("t1", schemaWithId)

      // compare with before
      assertEqual(spark.table("t1").schema,
        previousSchema, ignorePhysicalName = false)

      val df3 = spark.createDataFrame(
        Seq(Row("str3", 3, "str3.1"), Row("str4", 4, "str4.1")).asJava,
        schemaWithId.add("c", StringType, true, withId(3))
      )
      df3.write
         .option("mergeSchema", "true")
         .format("delta")
         .mode("append")
         .saveAsTable("t1")

      val curMaxId2 = DeltaColumnMapping.findMaxColumnId(schemaWithId) + 1
      checkProperties("t1",
        readerVersion = 2,
        writerVersion = 5,
        mode = Some(mode),
        curMaxId = curMaxId2)
      checkSchema("t1", schemaWithId.add("c", StringType, true, withId(3)))
    }
  }

  testColumnMapping(s"try modifying restricted max id property should fail") { mode =>
    withTable("t1") {
      val e = intercept[UnsupportedOperationException] {
        createTableWithSQLAPI(
          "t1",
          Map(DeltaConfigs.COLUMN_MAPPING_MODE.key -> mode,
              DeltaConfigs.COLUMN_MAPPING_MAX_ID.key -> "100"),
          withColumnIds = true,
          nested = true)
      }
      assert(e.getMessage.contains(s"The Delta table configuration " +
        s"${DeltaConfigs.COLUMN_MAPPING_MAX_ID.key} cannot be specified by the user"))
    }

    withTable("t1") {
      createTableWithSQLAPI(
          "t1",
          Map(DeltaConfigs.COLUMN_MAPPING_MODE.key -> mode),
          withColumnIds = true,
          nested = true)

      val e2 = intercept[UnsupportedOperationException] {
        alterTableWithProps("t1", Map(DeltaConfigs.COLUMN_MAPPING_MAX_ID.key -> "100"))
      }

      assert(e2.getMessage.contains(s"The Delta table configuration " +
        s"${DeltaConfigs.COLUMN_MAPPING_MAX_ID.key} cannot be specified by the user"))
    }

    withTable("t1") {
      val e = intercept[UnsupportedOperationException] {
        createTableWithDataFrameAPI(
          "t1",
          Map(DeltaConfigs.COLUMN_MAPPING_MODE.key -> mode,
              DeltaConfigs.COLUMN_MAPPING_MAX_ID.key -> "100"),
          withColumnIds = true,
          nested = true)
      }
      assert(e.getMessage.contains(s"The Delta table configuration " +
        s"${DeltaConfigs.COLUMN_MAPPING_MAX_ID.key} cannot be specified by the user"))
    }
  }

  testColumnMapping("physical data and partition schema") { mode =>
    withTable("t1") {
      // column ids will be dropped, having the options here to make sure such happens
      createTableWithSQLAPI("t1",
        Map(DeltaConfigs.COLUMN_MAPPING_MODE.key -> mode),
        withColumnIds = true,
        randomIds = true)

      val metadata =
        DeltaLog.forTable(spark, TableIdentifier("t1")).update().metadata

      assertEqual(metadata.schema, schemaWithId)
      assertEqual(metadata.schema, StructType(metadata.partitionSchema ++ metadata.dataSchema))
    }
  }

  testColumnMapping("block CONVERT TO DELTA") { mode =>
    withSQLConf(DeltaConfigs.COLUMN_MAPPING_MODE.defaultTablePropertyKey -> mode) {
      withTempDir { tablePath =>
        val tempDir = tablePath.getCanonicalPath
        val df1 = Seq(0).toDF("id")
          .withColumn("key1", lit("A1"))
          .withColumn("key2", lit("A2"))

        df1.write
          .partitionBy(Seq("key1"): _*)
          .format("parquet")
          .mode("overwrite")
          .save(tempDir)

        val e = intercept[UnsupportedOperationException] {
          sql(s"convert to delta parquet.`$tempDir` partitioned by (key1 String)")
        }
        assert(e.getMessage.contains(s"cannot be set to `$mode` when using CONVERT TO DELTA"))
      }
    }
  }

  testColumnMapping(
    "column mapping batch scan should detect physical name changes",
    enableSQLConf = true
  ) { _ =>
    withTempDir { dir =>
      spark.range(10).toDF("id")
        .write.format("delta").save(dir.getCanonicalPath)
      // Analysis phase
      val df = spark.read.format("delta").load(dir.getCanonicalPath)
      // Overwrite schema but with same logical schema
      spark.range(10).toDF("id")
        .write.format("delta").option("overwriteSchema", "true").mode("overwrite")
        .save(dir.getCanonicalPath)
      // The previous analyzed DF no longer is able to read the data any more because it generates
      // new physical name for the underlying columns, so we should fail.
      assert {
        intercept[DeltaAnalysisException] {
          df.collect()
        }.getErrorClass == "DELTA_SCHEMA_CHANGE_SINCE_ANALYSIS"
      }
      // See we can't read back the same data any more
      withSQLConf(DeltaSQLConf.DELTA_SCHEMA_ON_READ_CHECK_ENABLED.key -> "false") {
        checkAnswer(
          df,
          (0 until 10).map(_ => Row(null))
        )
      }
    }
  }

  protected def testPartitionPath(tableName: String)(createFunc: Boolean => Unit): Unit = {
    withTable(tableName) {
      Seq(true, false).foreach { isPartitioned =>
        spark.sql(s"drop table if exists $tableName")
        createFunc(isPartitioned)
        val snapshot = DeltaLog.forTable(spark, TableIdentifier(tableName)).update()
        val prefixLen = DeltaConfigs.RANDOM_PREFIX_LENGTH.fromMetaData(snapshot.metadata)
        Seq(("str3", 3), ("str4", 4)).toDF(schema.fieldNames: _*)
          .write.format("delta").mode("append").saveAsTable(tableName)
        checkAnswer(spark.table(tableName),
          Row("str1", 1) :: Row("str2", 2) :: Row("str3", 3) :: Row("str4", 4) :: Nil)
        // both new table writes and appends should use prefix
        val pattern = s"[A-Za-z0-9]{$prefixLen}/part-.*parquet"
        assert(snapshot.allFiles.collect().map(_.path).forall(_.matches(pattern)))
      }
    }
  }

  // Copied verbatim from the "valid replaceWhere" test in DeltaSuite
  protected def testReplaceWhere(): Unit =
    Seq(true, false).foreach { enabled =>
      withSQLConf(DeltaSQLConf.REPLACEWHERE_DATACOLUMNS_ENABLED.key -> enabled.toString) {
        Seq(true, false).foreach { partitioned =>
          // Skip when it's not enabled and not partitioned.
          if (enabled || partitioned) {
            withTempDir { dir =>
              val writer = Seq(1, 2, 3, 4).toDF()
                .withColumn("is_odd", $"value" % 2 =!= 0)
                .withColumn("is_even", $"value" % 2 === 0)
                .write
                .format("delta")

              if (partitioned) {
                writer.partitionBy("is_odd").save(dir.toString)
              } else {
                writer.save(dir.toString)
              }

              def data: DataFrame = spark.read.format("delta").load(dir.toString)

              Seq(5, 7).toDF()
                .withColumn("is_odd", $"value" % 2 =!= 0)
                .withColumn("is_even", $"value" % 2 === 0)
                .write
                .format("delta")
                .mode("overwrite")
                .option(DeltaOptions.REPLACE_WHERE_OPTION, "is_odd = true")
                .save(dir.toString)
              checkAnswer(
                data,
                Seq(2, 4, 5, 7).toDF()
                  .withColumn("is_odd", $"value" % 2 =!= 0)
                  .withColumn("is_even", $"value" % 2 === 0))

              // replaceWhere on non-partitioning columns if enabled.
              if (enabled) {
                Seq(6, 8).toDF()
                  .withColumn("is_odd", $"value" % 2 =!= 0)
                  .withColumn("is_even", $"value" % 2 === 0)
                  .write
                  .format("delta")
                  .mode("overwrite")
                  .option(DeltaOptions.REPLACE_WHERE_OPTION, "is_even = true")
                  .save(dir.toString)
                checkAnswer(
                  data,
                  Seq(5, 6, 7, 8).toDF()
                    .withColumn("is_odd", $"value" % 2 =!= 0)
                    .withColumn("is_even", $"value" % 2 === 0))
              }
            }
          }
        }
      }
    }

  testColumnMapping("valid replaceWhere", enableSQLConf = true) { _ =>
    testReplaceWhere()
  }

  protected def verifyUpgradeAndTestSchemaEvolution(tableName: String): Unit = {
    checkProperties(tableName,
      readerVersion = 2,
      writerVersion = 5,
      mode = Some("name"),
      curMaxId = 4)
    checkSchema(tableName, schemaWithIdNested)
    val expectedSchema = new StructType()
      .add("a", StringType, true, withIdAndPhysicalName(1, "a"))
      .add("b",
        new StructType()
          .add("c", StringType, true, withIdAndPhysicalName(3, "c"))
          .add("d", IntegerType, true, withIdAndPhysicalName(4, "d")),
        true,
        withIdAndPhysicalName(2, "b"))

    assertEqual(
      DeltaLog.forTable(spark, TableIdentifier(tableName)).update().schema,
      expectedSchema,
      ignorePhysicalName = false)

    checkAnswer(spark.table(tableName), dfWithoutIdsNested(spark))

    // test schema evolution
    val newNestedData =
      spark.createDataFrame(
        Seq(Row("str3", Row("str1.3", 3), "new value")).asJava,
        schemaNested.add("e", StringType))
    newNestedData.write.format("delta")
      .option("mergeSchema", "true")
      .mode("append").saveAsTable(tableName)
    checkAnswer(
      spark.table(tableName),
      dfWithoutIdsNested(spark).withColumn("e", lit(null)).union(newNestedData))

    val newTableSchema = DeltaLog.forTable(spark, TableIdentifier(tableName)).update().schema
    val newPhysicalName = DeltaColumnMapping.getPhysicalName(newTableSchema("e"))

    // physical name of new column should be GUID, not display name
    assert(newPhysicalName.startsWith("col-"))
    assertEqual(
      newTableSchema,
      expectedSchema.add("e", StringType, true, withIdAndPhysicalName(5, newPhysicalName)),
      ignorePhysicalName = false)
  }

  test("change mode on new protocol table") {
    withTable("t1") {
      createTableWithSQLAPI(
        "t1",
        isPartitioned = true,
        nested = true,
        props = Map(
          DeltaConfigs.MIN_READER_VERSION.key -> "2",
          DeltaConfigs.MIN_WRITER_VERSION.key -> "5"))

        alterTableWithProps("t1", Map(
          DeltaConfigs.COLUMN_MAPPING_MODE.key -> "name"))
      verifyUpgradeAndTestSchemaEvolution("t1")
    }
  }

  test("upgrade first and then change mode") {
    withTable("t1") {
      createTableWithSQLAPI("t1", isPartitioned = true, nested = true)
      alterTableWithProps("t1", Map(
        DeltaConfigs.MIN_READER_VERSION.key -> "2",
        DeltaConfigs.MIN_WRITER_VERSION.key -> "5"))

        alterTableWithProps("t1", Map(
          DeltaConfigs.COLUMN_MAPPING_MODE.key -> "name"))
      verifyUpgradeAndTestSchemaEvolution("t1")
    }
  }

  test("upgrade and change mode in one ALTER TABLE cmd") {
    withTable("t1") {
      createTableWithSQLAPI("t1", isPartitioned = true, nested = true)

        alterTableWithProps("t1", Map(
          DeltaConfigs.COLUMN_MAPPING_MODE.key -> "name",
          DeltaConfigs.MIN_READER_VERSION.key -> "2",
          DeltaConfigs.MIN_WRITER_VERSION.key -> "5"))
      verifyUpgradeAndTestSchemaEvolution("t1")
    }
  }

  test("illegal mode changes") {
    val oldModes = Seq("none") ++ supportedModes
    val newModes = Seq("none") ++ supportedModes
    val upgrade = Seq(true, false)
    oldModes.foreach { oldMode =>
      newModes.foreach { newMode =>
        upgrade.foreach { ug =>
          val oldProps = Map(DeltaConfigs.COLUMN_MAPPING_MODE.key -> oldMode)
          val newProps = Map(DeltaConfigs.COLUMN_MAPPING_MODE.key -> newMode) ++
            (if (!ug) Map.empty else Map(
              DeltaConfigs.MIN_READER_VERSION.key -> "2",
              DeltaConfigs.MIN_WRITER_VERSION.key -> "5"))

          if (oldMode != newMode && !(oldMode == "none" && newMode == "name")) {
            Given(s"old mode: $oldMode, new mode: $newMode, upgrade: $ug")
            val e = intercept[UnsupportedOperationException] {
              withTable("t1") {
                createTableWithSQLAPI("t1", props = oldProps)
                alterTableWithProps("t1", props = newProps)
              }
            }
            assert(e.getMessage.contains("Changing column mapping mode from"))
          }
        }
      }
    }
  }

  test("legal mode change without explicit upgrade") {
    val e = intercept[UnsupportedOperationException] {
      withTable("t1") {
        createTableWithSQLAPI("t1")
        alterTableWithProps("t1", props = Map(
          DeltaConfigs.COLUMN_MAPPING_MODE.key -> "name"))
      }
    }
    assert(e.getMessage.contains("Your current table protocol version does not" +
      " support changing column mapping modes"))
  }

  test("getPhysicalNameFieldMap") {
    // To keep things simple, we use schema `schemaWithPhysicalNamesNested` such that the
    // physical name is just the logical name repeated three times.

    val actual = DeltaColumnMapping
      .getPhysicalNameFieldMap(schemaWithPhysicalNamesNested)
      .map { case (physicalPath, field) => (physicalPath, field.name) }

    val expected = Map[Seq[String], String](
      Seq("aaa") -> "a",
      Seq("bbb") -> "b",
      Seq("bbb", "ccc") -> "c",
      Seq("bbb", "ddd") -> "d",
      Seq("bbb", "foo.foo.foo.bar.bar.bar") -> "foo.bar",
      Seq("bbb", "foo.foo.foo.bar.bar.bar", "fff") -> "f",
      Seq("ggg") -> "g",
      Seq("ggg", "ccc") -> "c",
      Seq("ggg", "ddd") -> "d",
      Seq("ggg", "foo.foo.foo.bar.bar.bar") -> "foo.bar",
      Seq("ggg", "foo.foo.foo.bar.bar.bar", "fff") -> "f",
      Seq("hhh") -> "h"
    )

    assert(expected === actual,
      s"""
         |The actual physicalName -> logicalName map
         |${actual.mkString("\n")}
         |did not equal the expected map
         |${expected.mkString("\n")}
         |""".stripMargin)
  }

  testColumnMapping("is drop/rename column operation") { mode =>
    import DeltaColumnMapping.{isDropColumnOperation, isRenameColumnOperation}

    withTable("t1") {
      def getMetadata(): MetadataAction = {
        DeltaLog.forTable(spark, TableIdentifier("t1")).update().metadata
      }

      createStrictSchemaTableWithDeltaTableApi(
        "t1",
        schemaWithPhysicalNamesNested,
        Map(DeltaConfigs.COLUMN_MAPPING_MODE.key -> mode)
      )

      // case 1: currentSchema compared with itself
      var currentMetadata = getMetadata()
      var newMetadata = getMetadata()
      assert(
        !isDropColumnOperation(newMetadata, currentMetadata) &&
          !isRenameColumnOperation(newMetadata, currentMetadata)
      )

      // case 2: add a top-level column
      sql("ALTER TABLE t1 ADD COLUMNS (ping INT)")
      currentMetadata = newMetadata
      newMetadata = getMetadata()
      assert(
        !isDropColumnOperation(newMetadata, currentMetadata) &&
          !isRenameColumnOperation(newMetadata, currentMetadata)
      )

      // case 3: add a nested column
      sql("ALTER TABLE t1 ADD COLUMNS (b.`foo.bar`.`my.new;col()` LONG)")
      currentMetadata = newMetadata
      newMetadata = getMetadata()
      assert(
        !isDropColumnOperation(newMetadata, currentMetadata) &&
          !isRenameColumnOperation(newMetadata, currentMetadata)
      )

      // case 4: drop a top-level column
      sql("ALTER TABLE t1 DROP COLUMN (ping)")
      currentMetadata = newMetadata
      newMetadata = getMetadata()
      assert(
        isDropColumnOperation(newMetadata, currentMetadata) &&
          !isRenameColumnOperation(newMetadata, currentMetadata)
      )

      // case 5: drop a nested column
      sql("ALTER TABLE t1 DROP COLUMN (g.`foo.bar`)")
      currentMetadata = newMetadata
      newMetadata = getMetadata()
      assert(
        isDropColumnOperation(newMetadata, currentMetadata) &&
          !isRenameColumnOperation(newMetadata, currentMetadata)
      )

      // case 6: rename a top-level column
      sql("ALTER TABLE t1 RENAME COLUMN a TO pong")
      currentMetadata = newMetadata
      newMetadata = getMetadata()
      assert(
        !isDropColumnOperation(newMetadata, currentMetadata) &&
          isRenameColumnOperation(newMetadata, currentMetadata)
      )

      // case 7: rename a nested column
      sql("ALTER TABLE t1 RENAME COLUMN b.c TO c2")
      currentMetadata = newMetadata
      newMetadata = getMetadata()
      assert(
        !isDropColumnOperation(newMetadata, currentMetadata) &&
          isRenameColumnOperation(newMetadata, currentMetadata)
      )
    }
  }

  Seq(true, false).foreach { cdfEnabled =>
    var shouldBlock = cdfEnabled

    val shouldBlockStr = if (shouldBlock) "should block" else "should not block"

    def checkHelper(
        log: DeltaLog,
        newSchema: StructType,
        action: Action,
        shouldFail: Boolean = shouldBlock): Unit = {
      val txn = log.startTransaction()
      txn.updateMetadata(txn.metadata.copy(schemaString = newSchema.json))

      if (shouldFail) {
        val e = intercept[DeltaUnsupportedOperationException] {
          txn.commit(Seq(action), DeltaOperations.ManualUpdate)
        }.getMessage
        assert(e == "Operation \"Manual Update\" is not allowed when the table has enabled " +
          "change data feed (CDF) and has undergone schema changes using DROP COLUMN or RENAME " +
          "COLUMN.")
      } else {
        txn.commit(Seq(action), DeltaOperations.ManualUpdate)
      }
    }

    val fileActions = Seq(
      AddFile("foo", Map.empty, 1L, 1L, dataChange = true),
      AddFile("foo", Map.empty, 1L, 1L, dataChange = true).remove) ++
      (if (cdfEnabled) AddCDCFile("foo", Map.empty, 1L) :: Nil else Nil)

    testColumnMapping(
      s"CDF and Column Mapping: $shouldBlockStr when CDF=$cdfEnabled",
      enableSQLConf = true) { mode =>

      def createTable(): Unit = {
        createStrictSchemaTableWithDeltaTableApi(
          "t1",
          schemaWithPhysicalNamesNested,
          Map(
            DeltaConfigs.COLUMN_MAPPING_MODE.key -> mode,
            DeltaConfigs.CHANGE_DATA_FEED.key -> cdfEnabled.toString
          )
        )
      }

      Seq("h", "b.`foo.bar`.f").foreach { colName =>

        // case 1: drop column with non-FileAction action should always pass
        withTable("t1") {
          createTable()
          val log = DeltaLog.forTable(spark, TableIdentifier("t1"))
          val droppedColumnSchema = sql("SELECT * FROM t1").drop(colName).schema
          checkHelper(log, droppedColumnSchema, SetTransaction("id", 1, None), shouldFail = false)
        }

        // case 2: rename column with FileAction should fail if $shouldBlock == true
        fileActions.foreach { fileAction =>
          withTable("t1") {
            createTable()
            val log = DeltaLog.forTable(spark, TableIdentifier("t1"))
            withSQLConf(
                DeltaConfigs.COLUMN_MAPPING_MODE.defaultTablePropertyKey -> mode) {
              withTable("t2") {
                sql("DROP TABLE IF EXISTS t2")
                sql("CREATE TABLE t2 USING DELTA AS SELECT * FROM t1")
                sql(s"ALTER TABLE t2 RENAME COLUMN $colName TO ii")
                val renamedColumnSchema = sql("SELECT * FROM t2").schema
                checkHelper(log, renamedColumnSchema, fileAction)
              }
            }
          }
        }

        // case 3: drop column with FileAction should fail if $shouldBlock == true
        fileActions.foreach { fileAction =>
          {
            withTable("t1") {
              createTable()
              val log = DeltaLog.forTable(spark, TableIdentifier("t1"))
              val droppedColumnSchema = sql("SELECT * FROM t1").drop(colName).schema
              checkHelper(log, droppedColumnSchema, fileAction)
            }
          }
        }
      }
    }
  }

  testColumnMapping("id and name mode should write field_id in parquet schema",
      modes = Some(Seq("name", "id"))) { mode =>
    withTable("t1") {
      createTableWithSQLAPI(
        "t1",
        Map(DeltaConfigs.COLUMN_MAPPING_MODE.key -> mode))
      val (log, snapshot) = DeltaLog.forTableWithSnapshot(spark, TableIdentifier("t1"))
      val files = snapshot.allFiles.collect()
      files.foreach { f =>
        val footer = ParquetFileReader.readFooter(
          log.newDeltaHadoopConf(),
          new Path(log.dataPath, f.path),
          ParquetMetadataConverter.NO_FILTER)
        footer.getFileMetaData.getSchema.getFields.asScala.foreach(f =>
          // getId.intValue will throw NPE if field id does not exist
          assert(f.getId.intValue >= 0)
        )
      }
    }
  }

  test("should block CM upgrade when commit has FileActions and CDF enabled") {
    Seq(true, false).foreach { cdfEnabled =>
      var shouldBlock = cdfEnabled

      withTable("t1") {
        createTableWithSQLAPI(
          "t1",
          props = Map(DeltaConfigs.CHANGE_DATA_FEED.key -> cdfEnabled.toString))

        val log = DeltaLog.forTable(spark, TableIdentifier("t1"))
        val currMetadata = log.snapshot.metadata
        val upgradeMetadata = currMetadata.copy(
          configuration = currMetadata.configuration ++ Map(
            DeltaConfigs.MIN_READER_VERSION.key -> "2",
            DeltaConfigs.MIN_WRITER_VERSION.key -> "5",
            DeltaConfigs.COLUMN_MAPPING_MODE.key -> NameMapping.name
          )
        )

        val txn = log.startTransaction()
        txn.updateMetadata(upgradeMetadata)

        if (shouldBlock) {
          val e = intercept[DeltaUnsupportedOperationException] {
            txn.commit(
              AddFile("foo", Map.empty, 1L, 1L, dataChange = true) :: Nil,
              DeltaOperations.ManualUpdate)
          }.getMessage
          assert(e == "Operation \"Manual Update\" is not allowed when the table has enabled " +
            "change data feed (CDF) and has undergone schema changes using DROP COLUMN or RENAME " +
            "COLUMN.")
        } else {
          txn.commit(
            AddFile("foo", Map.empty, 1L, 1L, dataChange = true) :: Nil,
            DeltaOperations.ManualUpdate)
        }
      }
    }
  }

  test("upgrade with dot column name should not be blocked") {
    testCreateTableColumnMappingMode(
      "t1",
      schemaWithDottedColumnNames,
      false,
      "name",
      createNewTable = false
    ) {
      sql(s"CREATE TABLE t1 (${schemaWithDottedColumnNames.toDDL}) USING DELTA")
      alterTableWithProps("t1", props = Map(
        DeltaConfigs.COLUMN_MAPPING_MODE.key -> "name",
        DeltaConfigs.MIN_READER_VERSION.key -> "2",
        DeltaConfigs.MIN_WRITER_VERSION.key -> "5"))
    }
  }

  test("explicit id matching") {
    // Explicitly disable field id reading to test id mode reinitialization
    val requiredConfs = Seq(
      SQLConf.PARQUET_FIELD_ID_READ_ENABLED,
      SQLConf.PARQUET_FIELD_ID_WRITE_ENABLED)

    requiredConfs.foreach { conf =>
      withSQLConf(conf.key -> "false") {
        val e = intercept[IllegalArgumentException] {
          withTable("t1") {
            createStrictSchemaTableWithDeltaTableApi(
              "t1",
              schemaWithIdNested,
              Map(DeltaConfigs.COLUMN_MAPPING_MODE.key -> "id")
            )
            val testData = spark.createDataFrame(
              Seq(Row("str3", Row("str1.3", 3))).asJava, schemaWithIdNested)
            testData.write.format("delta").mode("append").saveAsTable("t1")
          }
        }
        assert(e.getMessage.contains(conf.key))
      }
    }

    // The above configs are enabled by default, so no need to explicitly enable.
    withTable("t1") {
      val testSchema = schemaWithIdNested.add("e", StringType, true, withId(5))
      val testData = spark.createDataFrame(
        Seq(Row("str3", Row("str1.3", 3), "str4")).asJava, testSchema)

      createStrictSchemaTableWithDeltaTableApi(
        "t1",
        testSchema,
        Map(DeltaConfigs.COLUMN_MAPPING_MODE.key -> "id")
      )

      testData.write.format("delta").mode("append").saveAsTable("t1")

      def read: DataFrame = spark.read.format("delta").table("t1")
      val deltaLog = DeltaLog.forTable(spark, TableIdentifier("t1"))

      def updateFieldIdFor(fieldName: String, newId: Int): Unit = {
        val currentMetadata = deltaLog.update().metadata
        val currentSchema = currentMetadata.schema
        val field = currentSchema(fieldName)
        deltaLog.withNewTransaction { txn =>
          val updated = field.copy(metadata =
            new MetadataBuilder().withMetadata(field.metadata)
              .putLong(DeltaColumnMapping.PARQUET_FIELD_ID_METADATA_KEY, newId)
              .putLong(DeltaColumnMapping.COLUMN_MAPPING_METADATA_ID_KEY, newId)
              .build())
          val newSchema = StructType(Seq(updated) ++ currentSchema.filter(_.name != field.name))
          txn.commit(currentMetadata.copy(
            schemaString = newSchema.json,
            configuration = currentMetadata.configuration ++
              // Just a big id to bypass the check
              Map(DeltaConfigs.COLUMN_MAPPING_MAX_ID.key -> "10000")) :: Nil, ManualUpdate)
        }
      }

      // Case 1: manually modify the schema to read a non-existing id
      updateFieldIdFor("a", 100)
      // Reading non-existing id should return null
      checkAnswer(read.select("a"), Row(null) :: Nil)

      // Case 2: manually modify the schema to read another field's id
      // First let's drop e, because Delta detects duplicated field
      sql(s"ALTER TABLE t1 DROP COLUMN e")
      // point to the dropped field <e>'s data
      updateFieldIdFor("a", 5)
      checkAnswer(read.select("a"), Row("str4"))
    }
  }

  test("drop and recreate external Delta table with name column mapping enabled") {
    withTempDir { dir =>
      withTable("t1") {
        val createExternalTblCmd: String =
          s"""
             |CREATE EXTERNAL TABLE t1 (a long)
             |USING DELTA
             |LOCATION '${dir.getCanonicalPath}'
             |TBLPROPERTIES('delta.columnMapping.mode'='name')""".stripMargin
        sql(createExternalTblCmd)
        // Add column and drop the old one to increment max column ID
        sql(s"ALTER TABLE t1 ADD COLUMN (b long)")
        sql(s"ALTER TABLE t1 DROP COLUMN a")
        sql(s"ALTER TABLE t1 RENAME COLUMN b to a")
        val log = DeltaLog.forTable(spark, dir.getCanonicalPath)
        val configBeforeDrop = log.update().metadata.configuration
        assert(configBeforeDrop("delta.columnMapping.maxColumnId") == "2")
        sql(s"DROP TABLE t1")
        sql(createExternalTblCmd)
        // Configuration after recreating the external table should match the config right
        // before initially dropping it.
        assert(log.update().metadata.configuration == configBeforeDrop)
        // Adding another column picks up from the last maxColumnId and increments it
        sql(s"ALTER TABLE t1 ADD COLUMN (c string)")
        assert(log.update().metadata.configuration("delta.columnMapping.maxColumnId") == "3")
      }
    }
  }

  test("replace external Delta table with name column mapping enabled") {
    withTempDir { dir =>
      withTable("t1") {
        val replaceExternalTblCmd: String =
          s"""
             |CREATE OR REPLACE TABLE t1 (a long)
             |USING DELTA
             |LOCATION '${dir.getCanonicalPath}'
             |TBLPROPERTIES('delta.columnMapping.mode'='name')""".stripMargin
        sql(replaceExternalTblCmd)
        // Add column and drop the old one to increment max column ID
        sql(s"ALTER TABLE t1 ADD COLUMN (b long)")
        sql(s"ALTER TABLE t1 DROP COLUMN a")
        sql(s"ALTER TABLE t1 RENAME COLUMN b to a")
        val log = DeltaLog.forTable(spark, dir.getCanonicalPath)
        assert(log.update().metadata.configuration("delta.columnMapping.maxColumnId") == "2")
        sql(replaceExternalTblCmd)
        // Configuration after replacing existing table should be like the table has started new.
        assert(log.update().metadata.configuration("delta.columnMapping.maxColumnId") == "1")
      }
    }
  }

  test("verify internal table properties only if property exists in spec and existing metadata") {
    val withoutMaxColumnId = Map[String, String]("delta.columnMapping.mode" -> "name")
    val maxColumnIdOne = Map[String, String](
      "delta.columnMapping.mode" -> "name",
      "delta.columnMapping.maxColumnId" -> "1"
    )
    val maxColumnIdOneWithOthers = Map[String, String](
      "delta.columnMapping.mode" -> "name",
      "delta.columnMapping.maxColumnId" -> "1",
      "dummy.property" -> "dummy"
    )
    val maxColumnIdTwo = Map[String, String](
      "delta.columnMapping.mode" -> "name",
      "delta.columnMapping.maxColumnId" -> "2"
    )
    // Max column ID is missing in first set of configs. So don't block on verification.
    assert(DeltaColumnMapping.verifyInternalProperties(withoutMaxColumnId, maxColumnIdOne))
    // Max column ID matches.
    assert(DeltaColumnMapping.verifyInternalProperties(maxColumnIdOne, maxColumnIdOneWithOthers))
    // Max column IDs don't match
    assert(!DeltaColumnMapping.verifyInternalProperties(maxColumnIdOne, maxColumnIdTwo))
  }

  test("column mapping upgrade with table features") {
    val testTableName = "columnMappingTestTable"
    withTable(testTableName) {
      val minReaderKey = DeltaConfigs.MIN_READER_VERSION.key
      val minWriterKey = DeltaConfigs.MIN_WRITER_VERSION.key
      sql(
        s"""CREATE TABLE $testTableName
           |USING DELTA
           |TBLPROPERTIES(
           |'$minReaderKey' = '2',
           |'$minWriterKey' = '7'
           |)
           |AS SELECT * FROM RANGE(1)
           |""".stripMargin)

      // [[DeltaColumnMapping.verifyAndUpdateMetadataChange]] should not throw an error. The table
      // does not need to support read table features too.
      val columnMappingMode = DeltaConfigs.COLUMN_MAPPING_MODE.key
      sql(
        s"""ALTER TABLE $testTableName SET TBLPROPERTIES(
           |'$columnMappingMode'='name'
           |)""".stripMargin)
    }
  }
}
