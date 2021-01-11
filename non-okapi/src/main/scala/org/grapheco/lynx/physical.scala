package org.grapheco.lynx

import org.opencypher.v9_0.ast._
import org.opencypher.v9_0.expressions.SemanticDirection.{BOTH, INCOMING, OUTGOING}
import org.opencypher.v9_0.expressions._
import org.opencypher.v9_0.util.symbols.{CTNode, CTRelationship}
import org.grapheco.lynx.DataFrameOps._
import scala.collection.mutable.ArrayBuffer

trait PhysicalPlanNode {
  def execute(ctx: PlanExecutionContext): DataFrame
}

trait PhysicalPlanner {
  def plan(logicalPlan: LogicalPlanNode): PhysicalPlanNode
}

class PhysicalPlannerImpl()(implicit runnerContext: CypherRunnerContext) extends PhysicalPlanner {
  override def plan(logicalPlan: LogicalPlanNode): PhysicalPlanNode = {
    logicalPlan match {
      case LogicalCreate(c: Create, in: Option[LogicalQuerySource]) => PhysicalCreate(c, in.map(plan(_)))
      case LogicalMatch(m: Match, in: Option[LogicalQuerySource]) => PhysicalMatch(m, in.map(plan(_)))
      case LogicalReturn(r: Return, in: Option[LogicalQuerySource]) => PhysicalReturn(r, in.map(plan(_)))
      case LogicalWith(w: With, in: Option[LogicalQuerySource]) => PhysicalWith(w, in.map(plan(_)))
      case LogicalQuery(LogicalSingleQuery(in)) => PhysicalSingleQuery(in.map(plan(_)))
    }
  }
}

trait AbstractPhysicalPlanNode extends PhysicalPlanNode {
  val runnerContext: CypherRunnerContext
  implicit val dataFrameOperator = runnerContext.dataFrameOperator
  implicit val expressionEvaluator = runnerContext.expressionEvaluator

  def eval(expr: Expression)(implicit ec: ExpressionContext): CypherValue = expressionEvaluator.eval(expr)

  def createUnitDataFrame(items: Seq[ReturnItem], ctx: PlanExecutionContext): DataFrame = {
    implicit val ec = ctx.expressionContext
    DataFrame.unit(items.map(item => item.name -> item.expression))
  }
}

case class PhysicalSingleQuery(in: Option[PhysicalPlanNode])(implicit val runnerContext: CypherRunnerContext) extends AbstractPhysicalPlanNode {
  override def execute(ctx: PlanExecutionContext): DataFrame =
    in.map(_.execute(ctx)).getOrElse(DataFrame.empty)
}

case class PhysicalCreate(c: Create, in: Option[PhysicalPlanNode])(implicit val runnerContext: CypherRunnerContext) extends AbstractPhysicalPlanNode {
  override def execute(ctx: PlanExecutionContext): DataFrame = {
    //create
    val nodes = ArrayBuffer[(Option[LogicalVariable], Node2Create)]()
    val rels = ArrayBuffer[Relationship2Create]()
    implicit val ec = ctx.expressionContext

    c.pattern.patternParts.foreach {
      case EveryPath(NodePattern(variable: Option[LogicalVariable], labels, properties, _)) =>
        nodes += variable -> Node2Create(labels.map(_.name), properties.map {
          case MapExpression(items) =>
            items.map({
              case (k, v) => k.name -> eval(v)
            })
        }.getOrElse(Seq.empty))

      case EveryPath(RelationshipChain(element, relationship, rightNode)) =>
        def nodeRef(pe: PatternElement): NodeRef2Create = {
          pe match {
            case NodePattern(variable, _, _, _) =>
              nodes.toMap.get(variable).map(ContextualNodeRef2Create(_)).getOrElse(throw new UnrecognizedVarException(variable))
          }
        }

        rels += Relationship2Create(relationship.types.map(_.name), relationship.properties.map {
          case MapExpression(items) =>
            items.map({
              case (k, v) => k.name -> eval(v)
            })
        }.getOrElse(Seq.empty[(String, CypherValue)]),
          nodeRef(element),
          nodeRef(rightNode)
        )

      case _ =>
    }

    runnerContext.graphModel.createElements(nodes.map(_._2).toArray, rels.toArray)
    DataFrame.empty
  }
}

case class Node2Create(labels: Seq[String], props: Seq[(String, CypherValue)]) {

}

case class Relationship2Create(types: Seq[String], props: Seq[(String, CypherValue)], startNodeRef: NodeRef2Create, endNodeRef: NodeRef2Create) {

}

sealed trait NodeRef2Create

case class StoredNodeRef2Create(id: CypherId) extends NodeRef2Create

case class ContextualNodeRef2Create(node: Node2Create) extends NodeRef2Create

case class PhysicalMatch(m: Match, in: Option[PhysicalPlanNode])(implicit val runnerContext: CypherRunnerContext) extends AbstractPhysicalPlanNode {
  override def execute(ctx: PlanExecutionContext): DataFrame = {
    //run match
    val Match(optional, Pattern(patternParts: Seq[PatternPart]), hints, where: Option[Where]) = m
    patternParts match {
      case Seq(EveryPath(element: PatternElement)) =>
        patternMatch(element)(ctx)
    }
  }

  private def patternMatch(element: PatternElement)(ctx: PlanExecutionContext): DataFrame = {
    element match {
      case NodePattern(
      Some(var0: LogicalVariable),
      labels: Seq[LabelName],
      properties: Option[Expression],
      baseNode: Option[LogicalVariable]) =>
        DataFrame(Seq(var0.name -> CTNode), () => {
          val nodes = if (labels.isEmpty)
            runnerContext.graphModel.nodes()
          else
            runnerContext.graphModel.nodes(labels.map(_.name), false)

          nodes.map(Seq(_))
        })

      case RelationshipChain(
      leftNode@NodePattern(var1, labels1: Seq[LabelName], properties1: Option[Expression], baseNode1: Option[LogicalVariable]),
      RelationshipPattern(variable: Option[LogicalVariable], types: Seq[RelTypeName], length: Option[Option[Range]], properties: Option[Expression], direction: SemanticDirection, legacyTypeSeparator: Boolean, baseRel: Option[LogicalVariable]),
      rightNode@NodePattern(var2, labels2: Seq[LabelName], properties2: Option[Expression], baseNode2: Option[LogicalVariable])
      ) =>
        DataFrame((variable.map(_.name -> CTRelationship) ++ ((var1 ++ var2).map(_.name -> CTNode))).toSeq, () => {
          val rels: Iterator[(CypherRelationship, Option[CypherNode], Option[CypherNode])] =
            runnerContext.graphModel.rels(types.map(_.name), labels1, labels2, var1.isDefined, var2.isDefined)
          rels.flatMap {
            rel => {
              val (v0, v1, v2) = rel
              direction match {
                case BOTH =>
                  Iterator.apply(
                    Seq(v0) ++ var1.map(_ => v1.get) ++ var2.map(_ => v2.get),
                    Seq(v0) ++ var1.map(_ => v2.get) ++ var2.map(_ => v1.get)
                  )
                case INCOMING =>
                  Iterator.single(Seq(v0) ++ var1.map(_ => v2.get) ++ var2.map(_ => v1.get))
                case OUTGOING =>
                  Iterator.single(Seq(v0) ++ var1.map(_ => v1.get) ++ var2.map(_ => v2.get))
              }
            }
          }
        })
    }
  }
}

case class PhysicalWith(w: With, in: Option[PhysicalPlanNode])(implicit val runnerContext: CypherRunnerContext) extends AbstractPhysicalPlanNode {
  override def execute(ctx: PlanExecutionContext): DataFrame = {
    (w, in) match {
      case (With(distinct, ReturnItems(includeExisting, items), orderBy, skip, limit: Option[Limit], where), None) =>
        createUnitDataFrame(items, ctx)
      case (With(distinct, ReturnItems(includeExisting, items), orderBy, skip, limit: Option[Limit], where), Some(sin)) =>
        //match (n) return n
        val df0 = sin.execute(ctx)
        val df1 = df0.project(items.map(x => x.name -> x.expression))(ctx.expressionContext)
        val df2 = df1.select(items.map(item => item.name -> item.alias.map(_.name)))
        if (distinct) {
          df2.distinct
        }
        else {
          df2
        }
    }
  }
}

case class PhysicalReturn(r: Return, in: Option[PhysicalPlanNode])(implicit val runnerContext: CypherRunnerContext) extends AbstractPhysicalPlanNode {
  override def execute(ctx: PlanExecutionContext): DataFrame = {
    (r, in) match {
      case (Return(distinct, ReturnItems(includeExisting, items), orderBy, skip, limit, excludedNames), Some(sin)) =>
        //match (n) return n
        val df0 = sin.execute(ctx)
        val df1 = df0.project(items.map(x => x.name -> x.expression))(ctx.expressionContext)
        val df2 = df1.select(items.map(item => item.name -> item.alias.map(_.name)))
        if (distinct) {
          df2.distinct
        }
        else {
          df2
        }
    }
  }
}

class UnrecognizedVarException(var0: Option[LogicalVariable]) extends LynxException
