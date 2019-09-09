/*
 * Copyright (c) 2002-2019 "Neo4j,"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.cypher.internal.runtime.spec.tests

import java.time.Duration
import java.time.temporal.ChronoUnit
import java.util.Collections

import org.neo4j.configuration.GraphDatabaseSettings
import org.neo4j.cypher.internal.runtime.spec._
import org.neo4j.cypher.internal.{CypherRuntime, RuntimeContext}
import org.neo4j.exceptions.CypherTypeException
import org.neo4j.values.storable.{DoubleValue, DurationValue, StringValue, Values}
import org.neo4j.values.virtual.ListValue

abstract class AggregationTestBase[CONTEXT <: RuntimeContext](
                                                               edition: Edition[CONTEXT],
                                                               runtime: CypherRuntime[CONTEXT],
                                                               sizeHint: Int
                                                             ) extends RuntimeTestSuite[CONTEXT](edition, runtime) {

  test("should count(*)") {
    // given
    nodeGraph(sizeHint, "Honey")

    // when
    val logicalQuery = new LogicalQueryBuilder(this)
      .produceResults("c")
      .aggregation(Seq.empty, Seq("count(*) AS c"))
      .allNodeScan("x")
      .build()

    val runtimeResult = execute(logicalQuery, runtime)

    // then
    runtimeResult should beColumns("c").withRows(singleRow(sizeHint))
  }

  test("should count(*) on single grouping column") {
    // given
    nodePropertyGraph(sizeHint, {
      case i: Int => Map("num" -> i, "name" -> s"bob${i % 10}")
    }, "Honey")

    // when
    val logicalQuery = new LogicalQueryBuilder(this)
      .produceResults("name", "c")
      .aggregation(Seq("x.name AS name"), Seq("count(*) AS c"))
      .allNodeScan("x")
      .build()

    val runtimeResult = execute(logicalQuery, runtime)

    // then
    runtimeResult should beColumns("name", "c").withRows(for (i <- 0 until 10) yield {
      Array(s"bob$i", sizeHint / 10)
    })
  }

  test("should count(*) on single primitive grouping column") {
    // given
    val (nodes, _) = circleGraph(sizeHint)

    // when
    val logicalQuery = new LogicalQueryBuilder(this)
      .produceResults("x", "c")
      .aggregation(Seq("x AS x"), Seq("count(*) AS c"))
      .expand("(x)--(y)")
      .allNodeScan("x")
      .build()

    val runtimeResult = execute(logicalQuery, runtime)

    // then
    runtimeResult should beColumns("x", "c").withRows(nodes.map { node =>
      Array(node, 2)
    })
  }

  test("should count(*) on single grouping column with nulls") {
    // given
    nodePropertyGraph(sizeHint, {
      case i: Int if i % 2 == 0 => Map("num" -> i, "name" -> s"bob${i % 10}")
      case i: Int if i % 2 == 1 => Map("num" -> i)
    }, "Honey")

    // when
    val logicalQuery = new LogicalQueryBuilder(this)
      .produceResults("name", "c")
      .aggregation(Seq("x.name AS name"), Seq("count(*) AS c"))
      .allNodeScan("x")
      .build()

    val runtimeResult = execute(logicalQuery, runtime)

    // then
    runtimeResult should beColumns("name", "c").withRows((for (i <- 0 until 10 by 2) yield {
      Array(s"bob$i", sizeHint / 10)
    }) :+ Array(null, sizeHint / 2))
  }

  test("should count(*) on single primitive grouping column with nulls") {
    // given
    val (unfilteredNodes, _) = circleGraph(sizeHint)
    val nodes = select(unfilteredNodes, nullProbability = 0.5)
    val input = batchedInputValues(sizeHint / 8, nodes.map(n => Array[Any](n)): _*).stream()

    // when
    val logicalQuery = new LogicalQueryBuilder(this)
      .produceResults("x", "c")
      .aggregation(Seq("x AS x"), Seq("count(*) AS c"))
      .expand("(x)--(y)")
      .input(nodes = Seq("x"))
      .build()

    val runtimeResult = execute(logicalQuery, runtime, input)

    // then
    val expected = for(node <- nodes if node != null) yield Array(node, 2)
    runtimeResult should beColumns("x", "c").withRows(expected)
  }

  test("should count(*) on two grouping columns") {
    // given
    nodePropertyGraph(sizeHint, {
      case i: Int => Map("num" -> i, "name" -> s"bob${i % 10}", "surname" -> s"bobbins${i / 100}")
    }, "Honey")

    // when
    val logicalQuery = new LogicalQueryBuilder(this)
      .produceResults("name", "surname", "c")
      .aggregation(Seq("x.name AS name", "x.surname AS surname"), Seq("count(*) AS c"))
      .allNodeScan("x")
      .build()

    val runtimeResult = execute(logicalQuery, runtime)

    // then
    runtimeResult should beColumns("name", "surname", "c").withRows(for (i <- 0 until 10; j <- 0 until sizeHint / 100) yield {
      Array(s"bob$i", s"bobbins$j", 10)
    })
  }

  test("should count(*) on two primitive grouping columns with nulls") {
    // given
    val (unfilteredNodes, _) = circleGraph(sizeHint)
    val nodes = select(unfilteredNodes, nullProbability = 0.5)
    val input = batchedInputValues(sizeHint / 8, nodes.map(n => Array[Any](n)): _*).stream()

    // when
    val logicalQuery = new LogicalQueryBuilder(this)
      .produceResults("x", "c")
      .aggregation(Seq("x AS x", "x2 AS x2"), Seq("count(*) AS c"))
      .projection("x AS x2")
      .expand("(x)--(y)")
      .input(nodes = Seq("x"))
      .build()

    val runtimeResult = execute(logicalQuery, runtime, input)

    // then
    val expected = for(node <- nodes if node != null) yield Array(node, 2)
    runtimeResult should beColumns("x", "c").withRows(expected)
  }

  test("should count(*) on three grouping columns") {
    // given
    nodePropertyGraph(sizeHint, {
      case i: Int => Map("num" -> i, "name" -> s"bob${i % 10}", "surname" -> s"bobbins${i / 100}", "dead" -> i % 2)
    }, "Honey")

    // when
    val logicalQuery = new LogicalQueryBuilder(this)
      .produceResults("name", "surname", "dead", "c")
      .aggregation(Seq("x.name AS name", "x.surname AS surname", "x.dead AS dead"), Seq("count(*) AS c"))
      .allNodeScan("x")
      .build()

    val runtimeResult = execute(logicalQuery, runtime)

    // then
    runtimeResult should beColumns("name", "surname", "dead", "c").withRows(for (i <- 0 until 10; j <- 0 until sizeHint / 100) yield {
      Array(s"bob$i", s"bobbins$j", i % 2, 10)
    })
  }

  test("should count(n.prop)") {
    // given
    nodePropertyGraph(sizeHint, {
      case i: Int if i % 2 == 0 => Map("num" -> i)
    }, "Honey")

    // when
    val logicalQuery = new LogicalQueryBuilder(this)
      .produceResults("c")
      .aggregation(Seq.empty, Seq("count(x.num) AS c"))
      .allNodeScan("x")
      .build()

    val runtimeResult = execute(logicalQuery, runtime)

    // then
    runtimeResult should beColumns("c").withSingleRow(sizeHint / 2)
  }

  test("should collect(n.prop)") {
    // given
    nodePropertyGraph(sizeHint, {
      case i: Int if i % 2 == 0 => Map("num" -> i)
    }, "Honey")

    // when
    val logicalQuery = new LogicalQueryBuilder(this)
      .produceResults("c")
      .aggregation(Seq.empty, Seq("collect(x.num) AS c"))
      .allNodeScan("x")
      .build()

    val runtimeResult = execute(logicalQuery, runtime)

    // then
    runtimeResult should beColumns("c").withRows(matching {
      // The order of the collected elements in the list can differ
      case Seq(Array(d:ListValue)) if d.asArray().toSeq.sorted(ANY_VALUE_ORDERING) == (0 until sizeHint by 2).map(Values.intValue) =>
    })
  }

  test("should sum(n.prop)") {
    // given
    nodePropertyGraph(sizeHint, {
      case i: Int if i % 2 == 0 => Map("num" -> i)
    }, "Honey")

    // when
    val logicalQuery = new LogicalQueryBuilder(this)
      .produceResults("c")
      .aggregation(Seq.empty, Seq("sum(x.num) AS c"))
      .allNodeScan("x")
      .build()

    val runtimeResult = execute(logicalQuery, runtime)

    // then
    runtimeResult should beColumns("c").withSingleRow((0 until sizeHint by 2).sum)
  }

  test("should fail sum() on mixed numbers and durations I") {
    assertFailOnMixedNumberAndDuration("sum(x)")
  }

  test("should fail avg() on mixed numbers and durations I") {
    assertFailOnMixedNumberAndDuration("avg(x)")
  }

  private def assertFailOnMixedNumberAndDuration(aggregatingFunction: String) {
    // when
    val NUMBER: Array[Any] = Array(1.0)
    val DURATION: Array[Any] = Array(Duration.of(1, ChronoUnit.NANOS))
    val logicalQuery = new LogicalQueryBuilder(this)
      .produceResults("c")
      .aggregation(Seq.empty, Seq(s"$aggregatingFunction AS c"))
      .input(variables = Seq("x"))
      .build()

    // then I
    intercept[CypherTypeException] {
      val input = inputValues(NUMBER, DURATION)
      consume(execute(logicalQuery, runtime, input))
    }

    // then II
    intercept[CypherTypeException] {
      val input = inputValues(DURATION, NUMBER)
      consume(execute(logicalQuery, runtime, input))
    }

    val batchSize = edition.getSetting(GraphDatabaseSettings.cypher_morsel_size_big).getOrElse(10).asInstanceOf[Int]
    val numberBatches = (0 until batchSize * 10).map(_ => NUMBER)
    val durationBatches = (0 until batchSize * 10).map(_ => DURATION)

    // then III
    intercept[CypherTypeException] {
      val batches: Seq[Array[Any]] = numberBatches ++ durationBatches
      val input = batchedInputValues(batchSize, batches:_*)
      consume(execute(logicalQuery, runtime, input))
    }

    // then IV
    intercept[CypherTypeException] {
      val batches: Seq[Array[Any]] = durationBatches ++ numberBatches
      val input = batchedInputValues(batchSize, batches:_*)
      consume(execute(logicalQuery, runtime, input))
    }
  }

  test("should min(n.prop)") {
    // given
    nodePropertyGraph(sizeHint, {
      case i: Int if i % 2 == 0 => Map("num" -> i)
    }, "Honey")

    // when
    val logicalQuery = new LogicalQueryBuilder(this)
      .produceResults("c")
      .aggregation(Seq.empty, Seq("min(x.num) AS c"))
      .allNodeScan("x")
      .build()

    val runtimeResult = execute(logicalQuery, runtime)

    // then
    runtimeResult should beColumns("c").withSingleRow((0 until sizeHint by 2).min)
  }

  test("should max(n.prop)") {
    // given
    nodePropertyGraph(sizeHint, {
      case i: Int if i % 2 == 0 => Map("num" -> i)
    }, "Honey")

    // when
    val logicalQuery = new LogicalQueryBuilder(this)
      .produceResults("c")
      .aggregation(Seq.empty, Seq("max(x.num) AS c"))
      .allNodeScan("x")
      .build()

    val runtimeResult = execute(logicalQuery, runtime)

    // then
    runtimeResult should beColumns("c").withSingleRow((0 until sizeHint by 2).max)
  }

  test("should avg(n.prop)") {
    // given
    nodePropertyGraph(sizeHint, {
      case i: Int if i % 2 == 0 => Map("num" -> (i + 1))
    }, "Honey")

    // when
    val logicalQuery = new LogicalQueryBuilder(this)
      .produceResults("c")
      .aggregation(Seq.empty, Seq("avg(x.num) AS c"))
      .allNodeScan("x")
      .build()

    val runtimeResult = execute(logicalQuery, runtime)

    // then
    runtimeResult should beColumns("c").withRows(matching {
      case Seq(Array(d:DoubleValue)) if tolerantEquals(sizeHint.toDouble / 2, d.value()) =>
    })
  }

  test("should avg(n.prop) with grouping") {
    // given
    nodePropertyGraph(sizeHint, {
      case i: Int => Map("num" -> (i + 1), "name" -> s"bob${i % 10}")
    }, "Honey")

    // when
    val logicalQuery = new LogicalQueryBuilder(this)
      .produceResults("name", "c")
      .aggregation(Seq("x.name AS name"), Seq("avg(x.num) AS c"))
      .allNodeScan("x")
      .build()

    val runtimeResult = execute(logicalQuery, runtime)

    // then
    val theMiddle = sizeHint.toDouble / 2
    val expectedBobCounts = Map(
      "bob0" -> (theMiddle - 4),
      "bob1" -> (theMiddle - 3),
      "bob2" -> (theMiddle - 2),
      "bob3" -> (theMiddle - 1),
      "bob4" -> theMiddle,
      "bob5" -> (theMiddle + 1),
      "bob6" -> (theMiddle + 2),
      "bob7" -> (theMiddle + 3),
      "bob8" -> (theMiddle + 4),
      "bob9" -> (theMiddle + 5)
    )
    runtimeResult should beColumns("name", "c").withRows(matching {
      case rows: Seq[_] if rows.size == expectedBobCounts.size && rows.forall {
        case Array(s:StringValue, d:DoubleValue) => tolerantEquals(expectedBobCounts(s.stringValue()), d.value())
      } =>
    })
  }

  test("should avg(n.prop) with durations") {
    // given
    nodePropertyGraph(sizeHint, {
      case i: Int if i % 2 == 0 => Map("num" -> Duration.of(i + 1, ChronoUnit.NANOS))
    }, "Honey")

    // when
    val logicalQuery = new LogicalQueryBuilder(this)
      .produceResults("c")
      .aggregation(Seq.empty, Seq("avg(x.num) AS c"))
      .allNodeScan("x")
      .build()

    def asMillis(nanos: Double) = nanos / 1000000
    val runtimeResult = execute(logicalQuery, runtime)
    // then
    runtimeResult should beColumns("c").withRows(matching {
      //convert to millis to be less sensitive to rounding errors
      case Seq(Array(d:DurationValue)) if tolerantEquals(asMillis(sizeHint.toDouble / 2), asMillis(d.get(ChronoUnit.NANOS))) =>
    })
  }

  test("should avg(n.prop) without numerical overflow") {
    // given
    nodePropertyGraph(sizeHint, {
      case i: Int if i % 1000 == 0 => Map("num" -> (Double.MaxValue - 2.0))
      case i: Int if i % 1000 == 1 => Map("num" -> (Double.MaxValue - 1.0))
    }, "Honey")

    // when
    val logicalQuery = new LogicalQueryBuilder(this)
      .produceResults("c")
      .aggregation(Seq.empty, Seq("avg(x.num) AS c"))
      .allNodeScan("x")
      .build()

    val runtimeResult = execute(logicalQuery, runtime)

    // then
    runtimeResult should beColumns("c").withRows(matching {
      case Seq(Array(d:DoubleValue)) if tolerantEquals(Double.MaxValue - 1.5, d.value()) =>
    })
  }

  test("should return zero for empty input") {
    // given nothing

    // when
    val logicalQuery = new LogicalQueryBuilder(this)
      .produceResults("countStar", "count", "avg", "collect", "max", "min", "sum")
      .aggregation(Seq.empty, Seq(
        "count(*) AS countStar",
        "count(x.num) AS count",
        "avg(x.num) AS avg",
        "collect(x.num) AS collect",
        "max(x.num) AS max",
        "min(x.num) AS min",
        "sum(x.num) AS sum"))
      .allNodeScan("x")
      .build()

    val runtimeResult = execute(logicalQuery, runtime)

    // then
    runtimeResult should beColumns("countStar", "count", "avg", "collect", "max", "min", "sum").withSingleRow(0, 0, null, Collections.emptyList(),  null, null, 0)
  }
}