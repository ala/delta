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

import org.apache.spark.sql.delta.sources.DeltaSQLConf

import org.apache.spark.sql.internal.SQLConf
import org.apache.spark.sql.types._

/**
 * Test suite covering implicit casting in INSERT operations when the type of the data to insert
 * doesn't match the type in Delta table.
 *
 * The casting behavior is (unfortunately) dependent on the API used to run the INSERT, e.g.
 * Dataframe V1 insertInto() vs V2 saveAsTable() or using SQL.
 * This suite intends to exhaustively cover all the ways INSERT can be run on a Delta table. See
 * [[DeltaInsertIntoTest]] for a list of these INSERT operations covered.
 */
class DeltaInsertIntoImplicitCastSuite extends DeltaInsertIntoTest {

  override def beforeAll(): Unit = {
    super.beforeAll()
    spark.conf.set(DeltaSQLConf.DELTA_STREAMING_SINK_ALLOW_IMPLICIT_CASTS.key, "true")
    spark.conf.set(SQLConf.ANSI_ENABLED.key, "true")
  }

  test("all test cases are implemented") {
    checkAllTestCasesImplemented()
  }

  for (schemaEvolution <- BOOLEAN_DOMAIN) {
    testInserts("insert with implicit up and down cast on top-level fields, " +
      s"schemaEvolution=$schemaEvolution")(
      initialData = TestData("a long, b int", Seq("""{ "a": 1, "b": 2 }""")),
      partitionBy = Seq("a"),
      overwriteWhere = "a" -> 1,
      insertData = TestData("a int, b long", Seq("""{ "a": 1, "b": 4 }""")),
      expectedResult = ExpectedResult.Success(
        expected = new StructType()
          .add("a", LongType)
          .add("b", IntegerType)),
      // The following insert operations don't implicitly cast the data but fail instead - see
      // following test covering failure for these cases. We should change this to offer consistent
      // behavior across all inserts.
      excludeInserts = insertsDataframe.intersect(insertsByName) - StreamingInsert,
      confs = Seq(DeltaSQLConf.DELTA_SCHEMA_AUTO_MIGRATE.key -> schemaEvolution.toString)
    )

    testInserts("insert with implicit up and down cast on top-level fields, " +
      s"schemaEvolution=$schemaEvolution")(
      initialData = TestData("a long, b int", Seq("""{ "a": 1, "b": 2 }""")),
      partitionBy = Seq("a"),
      overwriteWhere = "a" -> 1,
      insertData = TestData("a int, b long", Seq("""{ "a": 1, "b": 4 }""")),
      expectedResult = ExpectedResult.Failure { ex =>
        checkError(
          ex,
           "DELTA_FAILED_TO_MERGE_FIELDS",
          parameters = Map(
            "currentField" -> "a",
            "updateField" -> "a"
        ))
      },
      includeInserts = insertsDataframe.intersect(insertsByName) - StreamingInsert,
      confs = Seq(DeltaSQLConf.DELTA_SCHEMA_AUTO_MIGRATE.key -> schemaEvolution.toString)
    )

    testInserts("insert with implicit up and down cast on fields nested in array, " +
      s"schemaEvolution=$schemaEvolution")(
      initialData = TestData("key int, a array<struct<x: long, y: int>>",
        Seq("""{ "key": 1, "a": [ { "x": 1, "y": 2 } ] }""")),
      partitionBy = Seq("key"),
      overwriteWhere = "key" -> 1,
      insertData = TestData("key int, a array<struct<x: int, y: long>>",
        Seq("""{ "key": 1, "a": [ { "x": 3, "y": 4 } ] }""")),
      expectedResult = ExpectedResult.Success(
        expected = new StructType()
          .add("key", IntegerType)
          .add("a", ArrayType(new StructType()
            .add("x", LongType)
            .add("y", IntegerType, nullable = true)))),
      // The following insert operations don't implicitly cast the data but fail instead - see
      // following test covering failure for these cases. We should change this to offer consistent
      // behavior across all inserts.
      excludeInserts = insertsDataframe.intersect(insertsByName) - StreamingInsert,
      confs = Seq(DeltaSQLConf.DELTA_SCHEMA_AUTO_MIGRATE.key -> schemaEvolution.toString)
    )

    testInserts("insert with implicit up and down cast on fields nested in array, " +
      s"schemaEvolution=$schemaEvolution")(
      initialData = TestData("key int, a array<struct<x: long, y: int>>",
        Seq("""{ "key": 1, "a": [ { "x": 1, "y": 2 } ] }""")),
      partitionBy = Seq("key"),
      overwriteWhere = "key" -> 1,
      insertData = TestData("key int, a array<struct<x: int, y: long>>",
        Seq("""{ "key": 1, "a": [ { "x": 3, "y": 4 } ] }""")),
      expectedResult = ExpectedResult.Failure { ex =>
        checkError(
          ex,
           "DELTA_FAILED_TO_MERGE_FIELDS",
          parameters = Map(
            "currentField" -> "a",
            "updateField" -> "a"
        ))
      },
      includeInserts = insertsDataframe.intersect(insertsByName) - StreamingInsert,
      confs = Seq(DeltaSQLConf.DELTA_SCHEMA_AUTO_MIGRATE.key -> schemaEvolution.toString)
    )

    testInserts("insert with implicit up and down cast on fields nested in map, " +
      s"schemaEvolution=$schemaEvolution")(
      initialData = TestData("key int, m map<string, struct<x: long, y: int>>",
        Seq("""{ "key": 1, "m": { "a": { "x": 1, "y": 2 } } }""")),
      partitionBy = Seq("key"),
      overwriteWhere = "key" -> 1,
      insertData = TestData("key int, m map<string, struct<x: int, y: long>>",
        Seq("""{ "key": 1, "m": { "a": { "x": 3, "y": 4 } } }""")),
      expectedResult = ExpectedResult.Success(
        expected = new StructType()
          .add("key", IntegerType)
          .add("m", MapType(StringType, new StructType()
            .add("x", LongType)
            .add("y", IntegerType)))),
      // The following insert operations don't implicitly cast the data but fail instead - see
      // following test covering failure for these cases. We should change this to offer consistent
      // behavior across all inserts.
      excludeInserts = insertsDataframe.intersect(insertsByName) - StreamingInsert,
      confs = Seq(DeltaSQLConf.DELTA_SCHEMA_AUTO_MIGRATE.key -> schemaEvolution.toString)
    )

    testInserts("insert with implicit up and down cast on fields nested in map, " +
      s"schemaEvolution=$schemaEvolution")(
      initialData = TestData("key int, m map<string, struct<x: long, y: int>>",
        Seq("""{ "key": 1, "m": { "a": { "x": 1, "y": 2 } } }""")),
      partitionBy = Seq("key"),
      overwriteWhere = "key" -> 1,
      insertData = TestData("key int, m map<string, struct<x: int, y: long>>",
        Seq("""{ "key": 1, "m": { "a": { "x": 3, "y": 4 } } }""")),
      expectedResult = ExpectedResult.Failure { ex =>
        checkError(
          ex,
           "DELTA_FAILED_TO_MERGE_FIELDS",
          parameters = Map(
            "currentField" -> "m",
            "updateField" -> "m"
        ))
      },
      includeInserts = insertsDataframe.intersect(insertsByName) - StreamingInsert,
      confs = Seq(DeltaSQLConf.DELTA_SCHEMA_AUTO_MIGRATE.key -> schemaEvolution.toString)
    )
  }
}
