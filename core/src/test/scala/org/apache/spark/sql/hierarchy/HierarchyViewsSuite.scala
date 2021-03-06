package org.apache.spark.sql.hierarchy

import org.apache.spark.Logging
import org.apache.spark.sql.types.Node
import org.apache.spark.sql.{AnalysisException, GlobalSapSQLContext, Row}
import org.scalatest.{BeforeAndAfter, FunSuite}

/**
 * Test suite for using a hierarchy in a view.
 */
// scalastyle:off file.size.limit
// scalastyle:off magic.number
class HierarchyViewsSuite
  extends FunSuite
  with BeforeAndAfter
  with GlobalSapSQLContext
  with HierarchyTestUtils
  with Logging {

  before {
    createOrgTable(sqlContext)
    createAddressesTable(sqlContext)
  }

  test("I can create an adjacency-list hierarchy view") {
    sqlContext
      .sql(s"CREATE TEMPORARY VIEW HV AS ${adjacencyListHierarchySQL(orgTbl, "name", "node")}")

    val result = sqlContext.sql(s"""| SELECT A.name, B.address, LEVEL(A.node)
                                    | FROM HV A FULL OUTER JOIN $addressesTable B
                                    | ON A.name = B.name""".stripMargin).collect().toSet

    val expected = Set(
      Row("THE BOSS", "Nice Street", 1),
      Row("The Other Middle Manager", null, 2),
      Row("The Middle Manager", "Acceptable Street", 2),
      Row("Senior Developer", "Near-Acceptable Street", 3),
      Row("Minion 1", null, 3),
      Row("Minion 2", null, 4),
      Row("Minion 3", "The Street", 4),
      Row(null, "Death Star", null)
    )
    assertResult(expected)(result)
  }

  test("I can create an level-based hierarchy view") {
    sqlContext
      .sql(s"CREATE TEMPORARY VIEW HV AS ${adjacencyListHierarchySQL(orgTbl, "name", "node")}")

    val result = sqlContext.sql(s"""| SELECT A.name, B.address, LEVEL(A.node)
                                    | FROM HV A FULL OUTER JOIN $addressesTable B
                                    | ON A.name = B.name""".stripMargin).collect().toSet

    val expected = Set(
      Row("THE BOSS", "Nice Street", 1),
      Row("The Other Middle Manager", null, 2),
      Row("The Middle Manager", "Acceptable Street", 2),
      Row("Senior Developer", "Near-Acceptable Street", 3),
      Row("Minion 1", null, 3),
      Row("Minion 2", null, 4),
      Row("Minion 3", "The Street", 4),
      Row(null, "Death Star", null)
    )
    assertResult(expected)(result)
  }

  test("I can self-join a hierarchy view") {
    sqlContext
      .sql(s"CREATE TEMPORARY VIEW HV AS ${adjacencyListHierarchySQL(orgTbl, "name", "node")}")

    val result = sqlContext.sql(
      s"""SELECT A.name, B.name
         |FROM HV A, HV B
         |WHERE IS_CHILD(A.node, B.node)=true""".stripMargin).collect().toSet

    val expected = Set(
      Row("Senior Developer" , "The Middle Manager"),
      Row("Minion 3" , "Senior Developer"),
      Row("Minion 2" , "Senior Developer"),
      Row("The Other Middle Manager" , "THE BOSS"),
      Row("The Middle Manager" , "THE BOSS"),
      Row("Minion 1" , "The Middle Manager")
    )

    assertResult(expected)(result)
  }

  test("I can use a view as a source table for the hierarchy") {
    createAnimalsTable(sqlContext)

    sqlContext.sql(s"CREATE TEMPORARY VIEW AnimalsView AS SELECT * FROM $animalsTable")

    sqlContext.sql(
      s"""CREATE TEMPORARY VIEW HV AS SELECT name, node FROM HIERARCHY (
         | USING AnimalsView AS v
         | JOIN PRIOR u ON v.pred = u.succ
         | START WHERE pred IS NULL
         | SET node
         | ) AS H""".stripMargin)

    val result = sqlContext.sql(
        s"""SELECT A.name
           | FROM HV A
           | WHERE IS_ROOT(A.node) = true""".stripMargin).collect()

    assertResult(Set(Row("Animal")))(result.toSet)
  }

  test("I can reuse hierarchy view") {
    sqlContext
      .sql(s"CREATE TEMPORARY VIEW HV1 AS ${adjacencyListHierarchySQL(orgTbl, "node", "name")}")

    sqlContext.sql(
      s"""| CREATE TEMPORARY VIEW HV2 AS SELECT A.name AS childName, B.name AS parentName
          | FROM HV1 A, HV1 B
          | WHERE IS_CHILD(A.node, B.node)=true""".stripMargin)

    val result = sqlContext.sql(s"""SELECT A.childName, B.address
                                    |FROM HV2 A FULL OUTER JOIN $addressesTable B
                                    |ON A.childName = B.name
                                    |WHERE A.parentName = 'THE BOSS'""".stripMargin).collect()

    val expected = Set(
      Row("The Middle Manager", "Acceptable Street"),
      Row("The Other Middle Manager", null)
    )
    assertResult(expected)(result.toSet)
  }

  test("I can not join different hierarchies together") {
    createAnimalsTable(sqlContext)

    sqlContext.sql(s"CREATE TEMPORARY VIEW AnimalsView AS " +
       adjacencyListHierarchySQL(animalsTable, "name", "node"))
    sqlContext.sql(s"CREATE TEMPORARY VIEW OrgView AS " +
      adjacencyListHierarchySQL(orgTbl, "name", "node"))
    val ex = intercept[AnalysisException] {
      sqlContext.sql(
        s"""SELECT A.name, B.name
           |FROM AnimalsView A FULL OUTER JOIN OrgView B
           |ON IS_CHILD(A.node, B.node)""".stripMargin).collect()
    }
    assert(ex.getMessage().contains("It is not allowed to use Node columns " +
      "from different hierarchies"))
  }

  test("I can not create a adjacency-list hierarchy with arbitrary parenthood expression") {
    val expectedErrorMessage = "The parenthood expression of an adjacency list hierarchy is " +
      "expected to be simple equality between two attributes of the same type, however " +
      "\\(pred#[0-9]*L < succ#[0-9]*L\\) is provided."

    createAnimalsTable(sqlContext)

    sqlContext.sql(s"""CREATE TEMPORARY VIEW AnimalsView AS (SELECT *
                     | FROM HIERARCHY
                     | (USING $animalsTable AS v JOIN PRIOR u ON v.pred < u.succ
                     | ORDER SIBLINGS BY ord ASC
                     | START WHERE pred IS NULL
                     | SET node) AS H)""".stripMargin)

    val ex = intercept[AnalysisException]{
      sqlContext.sql("SELECT * FROM AnimalsView").collect()
    }

    assert(ex.message.matches(expectedErrorMessage))
  }

  test("I can not create a level-based hierarchy with levels of different types") {
    createOrgTable(sqlContext)

    sqlContext.sql(s"""CREATE TEMPORARY VIEW AnimalsView AS (
                   |SELECT * FROM HIERARCHY (USING $orgTbl WITH LEVELS (name, succ) MATCH
                   |PATH SET Node) AS H)""".stripMargin).collect()

    // let's try to create a leveled hierarchy with name (string) and succ (long).
    val ex = intercept[AnalysisException] {
      sqlContext.sql("SELECT * FROM AnimalsView").collect()
    }
    assertResult("A level-based hierarchy expects all level columns to be of the same type, " +
      "however columns of the following types are provided: StringType,LongType.")(ex.message)
  }

  test("I can not create a level-based hierarchy with arbitrary matchers (VORASPARK-273") {
    createOrgTable(sqlContext)

    sqlContext.sql(s"""CREATE TEMPORARY VIEW AnimalsView AS (
                       |SELECT * FROM HIERARCHY (USING $orgTbl WITH LEVELS (name) MATCH
                       |NAME SET Node) AS H)""".stripMargin).collect()

    // let's try to create a leveled hierarchy with name (string) and succ (long).
    val ex = intercept[AnalysisException] {
      sqlContext.sql("SELECT * FROM AnimalsView").collect()
    }
    assertResult("Level-based hierarchy currently only supports PATH levels, check VORASPARK-273 " +
      "for more information.")(ex.message)
  }

  test("Implicit exclusion of Node column from top-level select works (bug 116471)") {
    sqlContext.sql(s"CREATE TEMPORARY VIEW HV AS ${adjacencyListHierarchySQL(orgTbl, "*")}")
    val result = sqlContext.sql(s"SELECT * FROM HV").collect().toSet
    val expected = Set(
      Row("Senior Developer", 2, 4, 1),
      Row("Minion 3", 4, 7, 2),
      Row("Minion 2", 4, 6, 1),
      Row("The Other Middle Manager", 1, 3, 2),
      Row("The Middle Manager", 1, 2, 1),
      Row("Minion 1", 2, 5, 2),
      Row("THE BOSS", null, 1, 1)
    )
    assertResult(expected)(result)
  }

  test("Explicit selection of the hierarchy node column works (bug 116471)") {
    sqlContext
      .sql(s"CREATE TEMPORARY VIEW HV AS ${adjacencyListHierarchySQL(orgTbl, "node", "name")}")
    val result = sqlContext.sql(s"SELECT node FROM HV WHERE name='Minion 1'").collect().toSet
    val expected = Set(Row(Node(Seq(1, 2, 5), "\"long\"", 6, 4, true, null)))
    assertResult(expected)(result)
  }
}
