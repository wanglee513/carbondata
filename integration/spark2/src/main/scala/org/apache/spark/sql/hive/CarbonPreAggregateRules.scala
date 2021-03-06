/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.hive

import scala.collection.JavaConverters._
import scala.collection.mutable.ArrayBuffer

import org.apache.spark.SPARK_VERSION
import org.apache.spark.sql._
import org.apache.spark.sql.CarbonExpressions.{CarbonScalaUDF, CarbonSubqueryAlias, MatchCast, MatchCastExpression}
import org.apache.spark.sql.catalyst.TableIdentifier
import org.apache.spark.sql.catalyst.analysis.{UnresolvedAlias, UnresolvedAttribute}
import org.apache.spark.sql.catalyst.expressions.{Alias, Attribute, AttributeReference, Cast, Divide, Expression, Literal, NamedExpression, ScalaUDF, SortOrder}
import org.apache.spark.sql.catalyst.expressions.aggregate._
import org.apache.spark.sql.catalyst.plans.logical._
import org.apache.spark.sql.catalyst.rules.Rule
import org.apache.spark.sql.execution.datasources.{FindDataSourceTable, LogicalRelation}
import org.apache.spark.sql.types._
import org.apache.spark.sql.util.CarbonException
import org.apache.spark.util.CarbonReflectionUtils

import org.apache.carbondata.core.constants.CarbonCommonConstants
import org.apache.carbondata.core.metadata.schema.table.{AggregationDataMapSchema, CarbonTable, DataMapSchema}
import org.apache.carbondata.core.preagg.{AggregateTableSelector, QueryColumn, QueryPlan}
import org.apache.carbondata.core.util.{CarbonUtil, ThreadLocalSessionInfo}
import org.apache.carbondata.spark.util.CarbonScalaUtil

/**
 * Class for applying Pre Aggregate rules
 * Responsibility.
 * 1. Check plan is valid plan for updating the parent table plan with child table
 * 2. Updated the plan based on child schema
 *
 * Rules for Upadating the plan
 * 1. Grouping expression rules
 *    1.1 Change the parent attribute reference for of group expression
 * to child attribute reference
 *
 * 2. Aggregate expression rules
 *    2.1 Change the parent attribute reference for of group expression to
 * child attribute reference
 *    2.2 Change the count AggregateExpression to Sum as count
 * is already calculated so in case of aggregate table
 * we need to apply sum to get the count
 *    2.2 In case of average aggregate function select 2 columns from aggregate table with
 * aggregation
 * sum and count. Then add divide(sum(column with sum), sum(column with count)).
 * Note: During aggregate table creation for average table will be created with two columns
 * one for sum(column) and count(column) to support rollup
 *
 * 3. Filter Expression rules.
 *    3.1 Updated filter expression attributes with child table attributes
 * 4. Update the Parent Logical relation with child Logical relation
 * 5. Order By Query rules.
 *    5.1 Update project list based on updated aggregate expression
 *    5.2 Update sort order attributes based on pre aggregate table
 * 6. timeseries function
 *    6.1 validate maintable has timeseries datamap
 *    6.2 timeseries function is valid function or not
 *
 * @param sparkSession
 * spark session
 */
case class CarbonPreAggregateQueryRules(sparkSession: SparkSession) extends Rule[LogicalPlan] {

  override def apply(plan: LogicalPlan): LogicalPlan = {
    var needAnalysis = true
    plan.transformExpressions {
      // first check if any preAgg scala function is applied it is present is in plan
      // then call is from create preaggregate table class so no need to transform the query plan
      case al@Alias(udf: ScalaUDF, name) if name.equalsIgnoreCase("preAgg") =>
        needAnalysis = false
        al
      case al@Alias(udf: ScalaUDF, name) if name.equalsIgnoreCase("preAggLoad") =>
        needAnalysis = false
        al
      // in case of query if any unresolve alias is present then wait for plan to be resolved
      // return the same plan as we can tranform the plan only when everything is resolved
      case unresolveAlias@UnresolvedAlias(_, _) =>
        needAnalysis = false
        unresolveAlias
      case attr@UnresolvedAttribute(_) =>
        needAnalysis = false
        attr
    }
    // if plan is not valid for transformation then return same plan
    if (!needAnalysis) {
      plan
    } else {
      // create buffer to collect all the column and its metadata information
      val list = scala.collection.mutable.HashSet.empty[QueryColumn]
      var isValidPlan = true
      val carbonTable = plan match {
        // matching the plan based on supported plan
        // if plan is matches with any case it will validate and get all
        // information required for transforming the plan

        // When plan has grouping expression, aggregate expression
        // subquery
        case Aggregate(groupingExp,
          aggregateExp,
          CarbonSubqueryAlias(_, logicalRelation: LogicalRelation))
          // only carbon query plan is supported checking whether logical relation is
          // is for carbon
          if logicalRelation.relation.isInstanceOf[CarbonDatasourceHadoopRelation]   &&
             logicalRelation.relation.asInstanceOf[CarbonDatasourceHadoopRelation].carbonRelation.
               metaData.hasAggregateDataMapSchema =>
          val (carbonTable, tableName) = getCarbonTableAndTableName(logicalRelation)
          // if it is valid plan then extract the query columns
          isValidPlan = extractQueryColumnsFromAggExpression(groupingExp,
            aggregateExp,
            carbonTable,
            tableName,
            list)
          carbonTable

        // below case for handling filter query
        // When plan has grouping expression, aggregate expression
        // filter expression
        case Aggregate(groupingExp,
          aggregateExp,
          Filter(filterExp,
          CarbonSubqueryAlias(_, logicalRelation: LogicalRelation)))
          // only carbon query plan is supported checking whether logical relation is
          // is for carbon
          if logicalRelation.relation.isInstanceOf[CarbonDatasourceHadoopRelation]   &&
             logicalRelation.relation.asInstanceOf[CarbonDatasourceHadoopRelation].carbonRelation.
               metaData.hasAggregateDataMapSchema =>
          val (carbonTable, tableName) = getCarbonTableAndTableName(logicalRelation)
          // if it is valid plan then extract the query columns
          isValidPlan = extractQueryColumnsFromAggExpression(groupingExp,
            aggregateExp,
            carbonTable,
            tableName,
            list)
          if(isValidPlan) {
            isValidPlan = !CarbonReflectionUtils.hasPredicateSubquery(filterExp)
          }
          // getting the columns from filter expression
          if(isValidPlan) {
            isValidPlan = extractQueryColumnFromFilterExp(filterExp, list, carbonTable, tableName)
          }
          carbonTable

        // When plan has grouping expression, aggregate expression
        // logical relation
        case Aggregate(groupingExp, aggregateExp, logicalRelation: LogicalRelation)
          // only carbon query plan is supported checking whether logical relation is
          // is for carbon
          if logicalRelation.relation.isInstanceOf[CarbonDatasourceHadoopRelation] &&
             logicalRelation.relation.asInstanceOf[CarbonDatasourceHadoopRelation].carbonRelation.
               metaData.hasAggregateDataMapSchema =>
          val (carbonTable, tableName) = getCarbonTableAndTableName(logicalRelation)
          // if it is valid plan then extract the query columns
          isValidPlan = extractQueryColumnsFromAggExpression(groupingExp,
            aggregateExp,
            carbonTable,
            tableName,
            list)
          carbonTable
        // case for handling aggregation, order by
        case Project(projectList,
          Sort(sortOrders,
            _,
            Aggregate(groupingExp,
              aggregateExp,
              CarbonSubqueryAlias(_, logicalRelation: LogicalRelation))))
          if logicalRelation.relation.isInstanceOf[CarbonDatasourceHadoopRelation] &&
             logicalRelation.relation.asInstanceOf[CarbonDatasourceHadoopRelation].carbonRelation.
               metaData.hasAggregateDataMapSchema =>
          val (carbonTable, tableName) = getCarbonTableAndTableName(logicalRelation)
          isValidPlan = extractQueryColumnsFromAggExpression(groupingExp,
            aggregateExp,
            carbonTable,
            tableName,
            list)
          if(isValidPlan) {
            list ++
            extractQueryColumnForOrderBy(Some(projectList), sortOrders, carbonTable, tableName)
          }
          carbonTable
        // case for handling aggregation, order by and filter
        case Project(projectList,
          Sort(sortOrders,
            _,
            Aggregate(groupingExp,
              aggregateExp,
              Filter(filterExp, CarbonSubqueryAlias(_, logicalRelation: LogicalRelation)))))
          if logicalRelation.relation.isInstanceOf[CarbonDatasourceHadoopRelation] &&
             logicalRelation.relation.asInstanceOf[CarbonDatasourceHadoopRelation].carbonRelation.
               metaData.hasAggregateDataMapSchema =>
          val (carbonTable, tableName) = getCarbonTableAndTableName(logicalRelation)
          isValidPlan = extractQueryColumnsFromAggExpression(groupingExp,
            aggregateExp,
            carbonTable,
            tableName,
            list)
          if(isValidPlan) {
            isValidPlan = !CarbonReflectionUtils.hasPredicateSubquery(filterExp)
          }
          if (isValidPlan) {
            list ++
            extractQueryColumnForOrderBy(Some(projectList), sortOrders, carbonTable, tableName)
            isValidPlan = extractQueryColumnFromFilterExp(filterExp, list, carbonTable, tableName)
          }
          carbonTable
        // case for handling aggregation with order by when only projection column exits
        case Sort(sortOrders,
          _,
          Aggregate(groupingExp,
            aggregateExp,
            CarbonSubqueryAlias(_, logicalRelation: LogicalRelation)))
          if logicalRelation.relation.isInstanceOf[CarbonDatasourceHadoopRelation] &&
             logicalRelation.relation.asInstanceOf[CarbonDatasourceHadoopRelation].carbonRelation.
               metaData.hasAggregateDataMapSchema =>
          val (carbonTable, tableName) = getCarbonTableAndTableName(logicalRelation)
          isValidPlan = extractQueryColumnsFromAggExpression(groupingExp,
            aggregateExp,
            carbonTable,
            tableName,
            list)
          if(isValidPlan) {
            list ++ extractQueryColumnForOrderBy(sortOrders = sortOrders,
              carbonTable = carbonTable,
              tableName = tableName)
          }
          carbonTable
        // case for handling aggregation with order by and filter when only projection column exits
        case Sort(sortOrders,
          _,
          Aggregate(groupingExp,
            aggregateExp,
            Filter(filterExp, CarbonSubqueryAlias(_, logicalRelation: LogicalRelation))))
          if logicalRelation.relation.isInstanceOf[CarbonDatasourceHadoopRelation] &&
             logicalRelation.relation.asInstanceOf[CarbonDatasourceHadoopRelation].carbonRelation.
               metaData.hasAggregateDataMapSchema =>
          val (carbonTable, tableName) = getCarbonTableAndTableName(logicalRelation)
          isValidPlan = extractQueryColumnsFromAggExpression(groupingExp,
            aggregateExp,
            carbonTable,
            tableName,
            list)
          if(isValidPlan) {
            isValidPlan = !CarbonReflectionUtils.hasPredicateSubquery(filterExp)
          }
          if(isValidPlan) {
            list ++ extractQueryColumnForOrderBy(sortOrders = sortOrders,
              carbonTable = carbonTable,
              tableName = tableName)
            isValidPlan = extractQueryColumnFromFilterExp(filterExp, list, carbonTable, tableName)
          }
          carbonTable
        case _ =>
          isValidPlan = false
          null
      }
      if (isValidPlan && null != carbonTable) {
        isValidPlan = isSpecificSegmentPresent(carbonTable)
      }
      // if plan is valid then update the plan with child attributes
      if (isValidPlan) {
        // getting all the projection columns
        val listProjectionColumn = list
          .filter(queryColumn => queryColumn.getAggFunction.isEmpty && !queryColumn.isFilterColumn)
          .toList
        // getting all the filter columns
        val listFilterColumn = list
          .filter(queryColumn => queryColumn.getAggFunction.isEmpty && queryColumn.isFilterColumn)
          .toList
        // getting all the aggregation columns
        val listAggregationColumn = list.filter(queryColumn => !queryColumn.getAggFunction.isEmpty)
          .toList
        // create a query plan object which will be used to select the list of pre aggregate tables
        // matches with this plan
        val queryPlan = new QueryPlan(listProjectionColumn.asJava,
          listAggregationColumn.asJava,
          listFilterColumn.asJava)
        // create aggregate table selector object
        val aggregateTableSelector = new AggregateTableSelector(queryPlan, carbonTable)
        // select the list of valid child tables
        val selectedDataMapSchemas = aggregateTableSelector.selectPreAggDataMapSchema()
        // if it does not match with any pre aggregate table return the same plan
        if (!selectedDataMapSchemas.isEmpty) {
          // filter the selected child schema based on size to select the pre-aggregate tables
          // that are nonEmpty
          val catalog = CarbonEnv.getInstance(sparkSession).carbonMetastore
          val relationBuffer = selectedDataMapSchemas.asScala.map { selectedDataMapSchema =>
            val identifier = TableIdentifier(
              selectedDataMapSchema.getRelationIdentifier.getTableName,
              Some(selectedDataMapSchema.getRelationIdentifier.getDatabaseName))
            val carbonRelation =
              catalog.lookupRelation(identifier)(sparkSession).asInstanceOf[CarbonRelation]
            val relation = sparkSession.sessionState.catalog.lookupRelation(identifier)
            (selectedDataMapSchema, carbonRelation, relation)
          }.filter(_._2.sizeInBytes != 0L)
          if (relationBuffer.isEmpty) {
            // If the size of relation Buffer is 0 then it means that none of the pre-aggregate
            // tables have date yet.
            // In this case we would return the original plan so that the query hits the parent
            // table.
            plan
          } else {
            // If the relationBuffer is nonEmpty then find the table with the minimum size.
            val (aggDataMapSchema, _, relation) = relationBuffer.minBy(_._2.sizeInBytes)
            val newRelation = new FindDataSourceTable(sparkSession).apply(relation)
            // transform the query plan based on selected child schema
            transformPreAggQueryPlan(plan, aggDataMapSchema, newRelation)
          }
        } else {
          plan
        }
      } else {
        plan
      }
    }
  }

  /**
   * Below method will be used to check whether specific segment is set for maintable
   * if it is present then no need to transform the plan and query will be executed on
   * maintable
   * @param carbonTable
   *                    parent table
   * @return is specific segment is present in session params
   */
  def isSpecificSegmentPresent(carbonTable: CarbonTable) : Boolean = {
    val carbonSessionInfo = ThreadLocalSessionInfo.getCarbonSessionInfo
    if (carbonSessionInfo != null) {
      carbonSessionInfo.getSessionParams
        .getProperty(CarbonCommonConstants.CARBON_INPUT_SEGMENTS +
                     carbonTable.getAbsoluteTableIdentifier.getCarbonTableIdentifier
                       .getDatabaseName + "." + carbonTable.getTableName, "").isEmpty
    } else {
      true
    }
  }

  /**
   * Below method will be used to extract the query columns from
   * filter expression
   * @param filterExp
   *                  filter expression
   * @param set
   *             query column list
   * @param carbonTable
   *                    parent table
   * @param tableName
   *                  table name
   * @return isvalid filter expression for aggregate
   */
  def extractQueryColumnFromFilterExp(filterExp: Expression,
      set: scala.collection.mutable.HashSet[QueryColumn],
      carbonTable: CarbonTable, tableName: String): Boolean = {
    // map to maintain attribute reference present in the filter to timeseries function
    // if applied this is added to avoid duplicate column
    val mapOfColumnSeriesFun = scala.collection.mutable.HashMap.empty[AttributeReference, String]
    val isValidPlan = true
    filterExp.transform {
      case attr: AttributeReference =>
        if (!mapOfColumnSeriesFun.get(attr).isDefined) {
          mapOfColumnSeriesFun.put(attr, null)
        }
        attr
      case udf@CarbonScalaUDF(_) =>
        // for handling timeseries function
        if (udf.asInstanceOf[ScalaUDF].function.getClass.getName.equalsIgnoreCase(
          "org.apache.spark.sql.execution.command.timeseries.TimeseriesFunction") &&
            CarbonUtil.hasTimeSeriesDataMap(carbonTable)) {
          mapOfColumnSeriesFun.put(udf.children(0).asInstanceOf[AttributeReference],
            udf.children(1).asInstanceOf[Literal].value.toString)
        } else {
          // for any other scala udf
          udf.transform {
            case attr: AttributeReference =>
              if (!mapOfColumnSeriesFun.get(attr).isDefined) {
                mapOfColumnSeriesFun.put(attr, null)
              }
              attr
          }
        }
        udf
    }
    mapOfColumnSeriesFun.foreach { f =>
        if (f._2 == null) {
          set +=
          getQueryColumn(f._1.name, carbonTable, tableName, isFilterColumn = true)
        } else {
          set += getQueryColumn(f._1.name,
            carbonTable,
            carbonTable.getTableName,
            isFilterColumn = true,
            timeseriesFunction = f._2)
        }
    }
    isValidPlan
  }
  /**
   * Below method will be used to extract columns from order by expression
   * @param projectList
   *                    project list from plan
   * @param sortOrders
   *                   sort order in plan
   * @param carbonTable
   *                    carbon table
   * @param tableName
   *                  table name
   * @return query columns from expression
   */
  def extractQueryColumnForOrderBy(projectList: Option[Seq[NamedExpression]] = None,
      sortOrders: Seq[SortOrder],
      carbonTable: CarbonTable,
      tableName: String): Seq[QueryColumn] = {
    val list = scala.collection.mutable.ListBuffer.empty[QueryColumn]
    if(projectList.isDefined) {
      projectList.get.map {
        proList =>
          proList.transform {
            case attr: AttributeReference =>
              val queryColumn = getQueryColumn(attr.name, carbonTable, tableName)
              if (null != queryColumn) {
                list += queryColumn
              }
              attr
          }
      }
    }
    sortOrders.foreach { sortOrder =>
        sortOrder.child match {
          case attr: AttributeReference =>
            val queryColumn = getQueryColumn(attr.name, carbonTable, tableName)
            if (null != queryColumn) {
              list += queryColumn
            }
        }
    }
    list
  }

  /**
   * Below method will be used to get the child attribute reference
   * based on parent name
   *
   * @param dataMapSchema
   * child schema
   * @param attributeReference
   * parent attribute reference
   * @param attributes
   * child logical relation
   * @param aggFunction
   * aggregation function applied on child
   * @param canBeNull
   * this is added for strict validation in which case child attribute can be
   * null and when it cannot be null
   * @return child attribute reference
   */
  def getChildAttributeReference(dataMapSchema: DataMapSchema,
      attributeReference: AttributeReference,
      attributes: Seq[AttributeReference],
      aggFunction: String = "",
      canBeNull: Boolean = false,
      timeseriesFunction: String = ""): AttributeReference = {
    val aggregationDataMapSchema = dataMapSchema.asInstanceOf[AggregationDataMapSchema];
    val columnSchema = if (aggFunction.isEmpty && timeseriesFunction.isEmpty) {
      aggregationDataMapSchema.getChildColByParentColName(attributeReference.name.toLowerCase)
    } else if (!aggFunction.isEmpty) {
      aggregationDataMapSchema.getAggChildColByParent(attributeReference.name.toLowerCase,
        aggFunction)
    } else {
      aggregationDataMapSchema
        .getTimeseriesChildColByParent(attributeReference.name.toLowerCase,
          timeseriesFunction)
    }
    // here column schema cannot be null, if it is null then aggregate table selection
    // logic has some problem
    if (!canBeNull && null == columnSchema) {
      throw new AnalysisException("Column does not exists in Pre Aggregate table")
    }
    if(null == columnSchema && canBeNull) {
      null
    } else {
      // finding the child attribute from child logical relation
      attributes.find(p => p.name.equals(columnSchema.getColumnName)).get
    }
  }

  /**
   * Below method will be used to transform the main table plan to child table plan
   * rules for transformming is as below.
   * 1. Grouping expression rules
   *    1.1 Change the parent attribute reference for of group expression
   * to child attribute reference
   *
   * 2. Aggregate expression rules
   *    2.1 Change the parent attribute reference for of group expression to
   * child attribute reference
   *    2.2 Change the count AggregateExpression to Sum as count
   * is already calculated so in case of aggregate table
   * we need to apply sum to get the count
   *    2.2 In case of average aggregate function select 2 columns from aggregate table with
   * aggregation sum and count. Then add divide(sum(column with sum), sum(column with count)).
   * Note: During aggregate table creation for average table will be created with two columns
   * one for sum(column) and count(column) to support rollup
   * 3. Filter Expression rules.
   *    3.1 Updated filter expression attributes with child table attributes
   * 4. Update the Parent Logical relation with child Logical relation
   * 5. Order by plan rules.
   *    5.1 Update project list based on updated aggregate expression
   *    5.2 Update sort order attributes based on pre aggregate table
   * 6. timeseries function
   *    6.1 validate parent table has timeseries datamap
   *    6.2 timeseries function is valid function or not
   *
   * @param logicalPlan
   * parent logical plan
   * @param aggDataMapSchema
   * select data map schema
   * @param childPlan
   * child carbon table relation
   * @return transformed plan
   */
  def transformPreAggQueryPlan(logicalPlan: LogicalPlan,
      aggDataMapSchema: DataMapSchema,
      childPlan: LogicalPlan): LogicalPlan = {
    val attributes = childPlan.output.asInstanceOf[Seq[AttributeReference]]
    logicalPlan.transform {
      // case for aggregation query
      case Aggregate(grExp, aggExp, child@CarbonSubqueryAlias(_, l: LogicalRelation))
        if l.relation.isInstanceOf[CarbonDatasourceHadoopRelation] &&
           l.relation.asInstanceOf[CarbonDatasourceHadoopRelation].carbonRelation.
             metaData.hasAggregateDataMapSchema =>
        val (updatedGroupExp, updatedAggExp, newChild, None) =
          getUpdatedExpressions(grExp,
            aggExp,
            child,
            None,
            aggDataMapSchema,
            attributes,
            childPlan)
        Aggregate(updatedGroupExp,
          updatedAggExp,
          newChild)
        // case of handling aggregation query with filter
      case Aggregate(grExp,
        aggExp,
        Filter(expression, child@CarbonSubqueryAlias(_, l: LogicalRelation)))
        if l.relation.isInstanceOf[CarbonDatasourceHadoopRelation] &&
           l.relation.asInstanceOf[CarbonDatasourceHadoopRelation].carbonRelation.
             metaData.hasAggregateDataMapSchema =>
        val (updatedGroupExp, updatedAggExp, newChild, updatedFilterExpression) =
          getUpdatedExpressions(grExp,
            aggExp,
            child,
            Some(expression),
            aggDataMapSchema,
            attributes,
            childPlan)
        Aggregate(updatedGroupExp,
          updatedAggExp,
          Filter(updatedFilterExpression.get,
            newChild))
        // case for aggregation query
      case Aggregate(grExp, aggExp, l: LogicalRelation)
        if l.relation.isInstanceOf[CarbonDatasourceHadoopRelation] &&
           l.relation.asInstanceOf[CarbonDatasourceHadoopRelation].carbonRelation.
             metaData.hasAggregateDataMapSchema =>
        val (updatedGroupExp, updatedAggExp, newChild, None) =
          getUpdatedExpressions(grExp,
            aggExp,
            l,
            None,
            aggDataMapSchema,
            attributes,
            childPlan)
        Aggregate(updatedGroupExp,
          updatedAggExp,
          newChild)
        // case for aggregation query with order by
      case Project(_,
        Sort(sortOrders,
          global,
          Aggregate(groupingExp,
            aggregateExp,
            subQuery@CarbonSubqueryAlias(_, l: LogicalRelation))))
        if l.relation.isInstanceOf[CarbonDatasourceHadoopRelation] &&
           l.relation.asInstanceOf[CarbonDatasourceHadoopRelation].carbonRelation.
             metaData.hasAggregateDataMapSchema =>
        val (updatedGroupExp, updatedAggExp, newChild, None) =
          getUpdatedExpressions(groupingExp,
            aggregateExp,
            subQuery,
            None,
            aggDataMapSchema,
            attributes,
            childPlan)
        val (updatedProjectList, updatedSortOrder) = transformPlanForOrderBy(updatedAggExp,
          sortOrders,
          aggDataMapSchema,
          attributes)
        Project(updatedProjectList,
          Sort(updatedSortOrder, global, Aggregate(updatedGroupExp, updatedAggExp, newChild)))
       // case for handling aggregation query with filter and order by
      case Project(_,
        Sort(sortOrders,
          global,
          Aggregate(groupingExp,
            aggregateExp,
            Filter(expression, subQuery@CarbonSubqueryAlias(_, l: LogicalRelation)))))
        if l.relation.isInstanceOf[CarbonDatasourceHadoopRelation] &&
           l.relation.asInstanceOf[CarbonDatasourceHadoopRelation].carbonRelation.
             metaData.hasAggregateDataMapSchema =>
        val (updatedGroupExp, updatedAggExp, newChild, updatedFilterExpression) =
          getUpdatedExpressions(groupingExp,
            aggregateExp,
            subQuery,
            Some(expression),
            aggDataMapSchema,
            attributes,
            childPlan)
        val (updatedProjectList, updatedSortOrder) = transformPlanForOrderBy(updatedAggExp,
          sortOrders,
          aggDataMapSchema,
          attributes)
        Project(updatedProjectList,
          Sort(updatedSortOrder, global, Aggregate(updatedGroupExp, updatedAggExp,
            Filter(updatedFilterExpression.get, newChild))))
      // case for handling aggregation with order by when only projection column exits
      case Sort(sortOrders,
        global,
        Aggregate(
          groupingExp,
          aggregateExp,
          subQuery@CarbonSubqueryAlias(_, logicalRelation: LogicalRelation)))
        if logicalRelation.relation.isInstanceOf[CarbonDatasourceHadoopRelation] &&
           logicalRelation.relation.asInstanceOf[CarbonDatasourceHadoopRelation].carbonRelation.
             metaData.hasAggregateDataMapSchema =>
        val (updatedGroupExp, updatedAggExp, newChild, None) =
          getUpdatedExpressions(groupingExp,
            aggregateExp,
            subQuery,
            None,
            aggDataMapSchema,
            attributes,
            childPlan)
        val (_, updatedSortOrder) = transformPlanForOrderBy(updatedAggExp,
          sortOrders,
          aggDataMapSchema,
          attributes)
        Sort(updatedSortOrder, global, Aggregate(updatedGroupExp, updatedAggExp, newChild))
      // case for handling aggregation with order by and filter when only projection column exits
      case Sort(sortOrders,
        global,
        Aggregate(groupingExp,
          aggregateExp,
          Filter(expression, subQuery@CarbonSubqueryAlias(_, l: LogicalRelation))))
        if l.relation.isInstanceOf[CarbonDatasourceHadoopRelation] &&
           l.relation.asInstanceOf[CarbonDatasourceHadoopRelation].carbonRelation.
             metaData.hasAggregateDataMapSchema =>
        val (updatedGroupExp, updatedAggExp, newChild, updatedFilterExpression) =
          getUpdatedExpressions(groupingExp,
            aggregateExp,
            subQuery,
            Some(expression),
            aggDataMapSchema,
            attributes,
            childPlan)
        val (_, updatedSortOrder) = transformPlanForOrderBy(updatedAggExp,
          sortOrders,
          aggDataMapSchema,
          attributes)
        Sort(updatedSortOrder, global, Aggregate(updatedGroupExp, updatedAggExp, newChild))
    }
  }

  /**
   * Below method will be used to updated the maintable plan for order by query
   * In case of order by we need to update project list and sort order attributes.
   *
   * @param aggregateExp
   *                     child table aggregate expression
   * @param sortOrders
   *                   sort order expression in maintable plan
   * @param aggDataMapSchema
   *                         child data map schema
   * @param attributes
   *                   child attributes
   * @return updated project list and updated sort order
   */
  def transformPlanForOrderBy(aggregateExp: Seq[NamedExpression],
      sortOrders: Seq[SortOrder], aggDataMapSchema: DataMapSchema,
      attributes: Seq[AttributeReference]): (Seq[NamedExpression], Seq[SortOrder]) = {
    val updatedProjectList = new ArrayBuffer[NamedExpression]()
    // getting the updated project list from aggregate expression
    aggregateExp.foreach{f => f.transform {
      // for projection column
      case alias@Alias(attr: AttributeReference, name) =>
        updatedProjectList += AttributeReference(name, attr.dataType, attr.nullable)(alias.exprId,
          alias.qualifier,
          alias.isGenerated)
        alias
        // for aggregaton column
      case alias@Alias(attr: AggregateExpression, name) =>
        updatedProjectList += AttributeReference(name, attr.dataType, attr.nullable)(alias.exprId,
          alias.qualifier,
          alias.isGenerated)
        alias
      case alias@Alias(exp: Expression, name) =>
        updatedProjectList += AttributeReference(name, exp.dataType, exp.nullable)(alias.exprId,
          alias.qualifier,
          alias.isGenerated)
        alias
    }
    }
    // getting the updated sort order
    val updatedSortOrders = sortOrders.map { order =>
      order.child match {
        case attr: AttributeReference =>
          val childAttribute = getChildAttributeReference(aggDataMapSchema,
            attr,
            attributes,
            canBeNull = true)
          // child attribute can be null only in case of alias in query
          // so in that case we need to update the sortorder based on new alias
          if (null != childAttribute) {
            val childExpression = getUpdatedSortOrderExpression(childAttribute, aggregateExp)
            SortOrder(childExpression, order.direction)
          } else {
            val childExpression = getUpdatedSortOrderExpression(attr, aggregateExp)
            SortOrder(childExpression, order.direction)
          }
      }
    }
    (updatedProjectList, updatedSortOrders)
  }
  /**
   * Below method will be used to get the updated expression for pre aggregated table.
   * It will replace the attribute of actual plan with child table attributes.
   * Updation will be done for below expression.
   * 1. Grouping expression
   * 2. aggregate expression
   * 3. child logical plan
   * 4. filter expression if present
   *
   * @param groupingExpressions
   * actual plan grouping expression
   * @param aggregateExpressions
   * actual plan aggregate expression
   * @param child
   * child logical plan
   * @param filterExpression
   * filter expression
   * @param aggDataMapSchema
   * pre aggregate table schema
   * @param attributes
   * pre aggregate table logical relation
   * @param aggPlan
   *                aggregate logical plan
   * @return tuple of(updated grouping expression,
   *         updated aggregate expression,
   *         updated child logical plan,
   *         updated filter expression if present in actual plan)
   */
  def getUpdatedExpressions(groupingExpressions: Seq[Expression],
      aggregateExpressions: Seq[NamedExpression],
      child: LogicalPlan, filterExpression: Option[Expression] = None,
      aggDataMapSchema: DataMapSchema,
      attributes: Seq[AttributeReference],
      aggPlan: LogicalPlan): (Seq[Expression], Seq[NamedExpression], LogicalPlan,
      Option[Expression]) = {
    // transforming the group by expression attributes with child attributes
    val updatedGroupExp = groupingExpressions.map { exp =>
      exp.transform {
        case attr: AttributeReference =>
          getChildAttributeReference(aggDataMapSchema, attr, attributes)
      }
    }
    // below code is for updating the aggregate expression.
    // Note: In case of aggregate expression updation we need to return alias as
    //       while showing the final result we need to show based on actual query
    //       for example: If query is "select name from table group by name"
    //       if we only update the attributes it will show child table column name in final output
    //       so for handling this if attributes does not have alias we need to return alias of
    // parent
    //       table column name
    // Rules for updating aggregate expression.
    // 1. If it matches with attribute reference return alias of child attribute reference
    // 2. If it matches with alias return same alias with child attribute reference
    // 3. If it matches with alias of any supported aggregate function return aggregate function
    // with child attribute reference. Please check class level documentation how when aggregate
    // function will be updated

    val updatedAggExp = aggregateExpressions.map {
      // case for attribute reference
      case attr: AttributeReference =>
        val childAttributeReference = getChildAttributeReference(aggDataMapSchema,
          attr,
          attributes)
        // returning the alias to show proper column name in output
        Alias(childAttributeReference,
          attr.name)(NamedExpression.newExprId,
          childAttributeReference.qualifier).asInstanceOf[NamedExpression]
      // case for alias
      case Alias(attr: AttributeReference, name) =>
        val childAttributeReference = getChildAttributeReference(aggDataMapSchema,
          attr,
          attributes)
        // returning alias with child attribute reference
        Alias(childAttributeReference,
          name)(NamedExpression.newExprId,
          childAttributeReference.qualifier).asInstanceOf[NamedExpression]
      // for aggregate function case
      case alias@Alias(attr: AggregateExpression, name) =>
        // get the updated aggregate aggregate function
        val aggExp = getUpdatedAggregateExpressionForChild(attr,
          aggDataMapSchema,
          attributes)
        // returning alias with child attribute reference
        Alias(aggExp,
          name)(NamedExpression.newExprId,
          alias.qualifier).asInstanceOf[NamedExpression]
      case alias@Alias(expression: Expression, name) =>
        val updatedExp =
          if (expression.isInstanceOf[ScalaUDF] &&
              expression.asInstanceOf[ScalaUDF].function.getClass.getName.equalsIgnoreCase(
                "org.apache.spark.sql.execution.command.timeseries.TimeseriesFunction")) {
          expression.asInstanceOf[ScalaUDF].transform {
            case attr: AttributeReference =>
            val childAttributeReference = getChildAttributeReference(aggDataMapSchema,
              attr,
              attributes,
              timeseriesFunction =
                expression.asInstanceOf[ScalaUDF].children(1).asInstanceOf[Literal].value.toString)
            childAttributeReference
          }
        } else {
          expression.transform{
            case attr: AttributeReference =>
              val childAttributeReference = getChildAttributeReference(aggDataMapSchema,
                attr,
                attributes)
              childAttributeReference
          }
        }
        Alias(updatedExp, name)(NamedExpression.newExprId,
          alias.qualifier).asInstanceOf[NamedExpression]
    }
    // transformaing the logical relation
    val newChild = child.transform {
      case _: LogicalRelation =>
        aggPlan
      case _: SubqueryAlias =>
        aggPlan match {
          case s: SubqueryAlias => s.child
          case others => others
    }
    }
    // updating the filter expression if present
    val updatedFilterExpression = if (filterExpression.isDefined) {
      val filterExp = filterExpression.get
      Some(filterExp.transform {
        case attr: AttributeReference =>
          getChildAttributeReference(aggDataMapSchema, attr, attributes)
      })
    } else {
      None
    }
    (updatedGroupExp, updatedAggExp, newChild, updatedFilterExpression)
  }

  /**
   * Below method will be used to get the updated sort order attribute
   * based on pre aggregate table
   * @param sortOrderAttr
   *                      sort order attributes reference
   * @param aggregateExpressions
   *                             aggregate expression
   * @return updated sortorder attribute
   */
  def getUpdatedSortOrderExpression(sortOrderAttr: AttributeReference,
      aggregateExpressions: Seq[NamedExpression]): Expression = {
    val updatedExpression = aggregateExpressions collectFirst {
      // in case of alias we need to match with alias name and when alias is not present
      // we need to compare with attribute reference name
      case alias@Alias(attr: AttributeReference, name)
        if attr.name.equalsIgnoreCase(sortOrderAttr.name) ||
           name.equalsIgnoreCase(sortOrderAttr.name) =>
          AttributeReference(name,
            sortOrderAttr.dataType,
            sortOrderAttr.nullable,
            sortOrderAttr.metadata)(alias.exprId, alias.qualifier, alias.isGenerated)
      case alias@Alias(_: Expression, name)
        if name.equalsIgnoreCase(sortOrderAttr.name) =>
          AttributeReference(name,
          sortOrderAttr.dataType,
          sortOrderAttr.nullable,
          sortOrderAttr.metadata)(alias.exprId, alias.qualifier, alias.isGenerated)
    }
    // any case it will match the condition, so no need to check whether updated expression is empty
    // or not
    updatedExpression.get
  }

  /**
   * Below method will be used to get the aggregate expression based on match
   * Aggregate expression updation rules
   * 1 Change the count AggregateExpression to Sum as count
   * is already calculated so in case of aggregate table
   * we need to apply sum to get the count
   * 2 In case of average aggregate function select 2 columns from aggregate table
   * with aggregation sum and count.
   * Then add divide(sum(column with sum), sum(column with count)).
   * Note: During aggregate table creation for average aggregation function
   * table will be created with two columns one for sum(column) and count(column)
   * to support rollup
   *
   * @param aggExp
   * aggregate expression
   * @param dataMapSchema
   * child data map schema
   * @param attributes
   * child logical relation
   * @return updated expression
   */
  def getUpdatedAggregateExpressionForChild(aggExp: AggregateExpression,
      dataMapSchema: DataMapSchema,
      attributes: Seq[AttributeReference]):
  Expression = {
    aggExp.aggregateFunction match {
      // Change the count AggregateExpression to Sum as count
      // is already calculated so in case of aggregate table
      // we need to apply sum to get the count
      case count@Count(Seq(attr: AttributeReference)) =>
        AggregateExpression(Sum(Cast(getChildAttributeReference(dataMapSchema,
          attr,
          attributes,
          count.prettyName),
          LongType)),
          aggExp.mode,
          isDistinct = false)
      case sum@Sum(attr: AttributeReference) =>
        AggregateExpression(Sum(getChildAttributeReference(dataMapSchema,
          attr,
          attributes,
          sum.prettyName)),
          aggExp.mode,
          isDistinct = false)
      case max@Max(attr: AttributeReference) =>
        AggregateExpression(Max(getChildAttributeReference(dataMapSchema,
          attr,
          attributes,
          max.prettyName)),
          aggExp.mode,
          isDistinct = false)
      case min@Min(attr: AttributeReference) =>
        AggregateExpression(Min(getChildAttributeReference(dataMapSchema,
          attr,
          attributes,
          min.prettyName)),
          aggExp.mode,
          isDistinct = false)
      case sum@Sum(MatchCast(attr: AttributeReference, changeDataType: DataType)) =>
        AggregateExpression(Sum(Cast(getChildAttributeReference(dataMapSchema,
          attr,
          attributes,
          sum.prettyName),
          changeDataType)),
          aggExp.mode,
          isDistinct = false)
      case min@Min(MatchCast(attr: AttributeReference, changeDataType: DataType)) =>
        AggregateExpression(Min(Cast(getChildAttributeReference(dataMapSchema,
          attr,
          attributes,
          min.prettyName),
          changeDataType)),
          aggExp.mode,
          isDistinct = false)
      case max@Max(MatchCast(attr: AttributeReference, changeDataType: DataType)) =>
        AggregateExpression(Max(Cast(getChildAttributeReference(dataMapSchema,
          attr,
          attributes,
          max.prettyName),
          changeDataType)),
          aggExp.mode,
          isDistinct = false)

      // In case of average aggregate function select 2 columns from aggregate table
      // with aggregation sum and count.
      // Then add divide(sum(column with sum), sum(column with count)).
      case Average(attr: AttributeReference) =>
        Divide(AggregateExpression(Sum(getChildAttributeReference(dataMapSchema,
          attr,
          attributes,
          "sum")),
          aggExp.mode,
          isDistinct = false),
          AggregateExpression(Sum(getChildAttributeReference(dataMapSchema,
            attr,
            attributes,
            "count")),
            aggExp.mode,
            isDistinct = false))
      // In case of average aggregate function select 2 columns from aggregate table
      // with aggregation sum and count.
      // Then add divide(sum(column with sum), sum(column with count)).
      case Average(MatchCast(attr: AttributeReference, changeDataType: DataType)) =>
        Divide(AggregateExpression(Sum(Cast(getChildAttributeReference(dataMapSchema,
          attr,
          attributes,
          "sum"),
          DoubleType)),
          aggExp.mode,
          isDistinct = false),
          AggregateExpression(Sum(Cast(getChildAttributeReference(dataMapSchema,
            attr,
            attributes,
            "count"),
            DoubleType)),
            aggExp.mode,
            isDistinct = false))
    }
  }

  /**
   * Method to get the carbon table and table name
   *
   * @param parentLogicalRelation
   * parent table relation
   * @return tuple of carbon table and table name
   */
  def getCarbonTableAndTableName(parentLogicalRelation: LogicalRelation): (CarbonTable, String) = {
    val carbonTable = parentLogicalRelation.relation.asInstanceOf[CarbonDatasourceHadoopRelation]
      .carbonRelation
      .metaData.carbonTable
    val tableName = carbonTable.getAbsoluteTableIdentifier.getCarbonTableIdentifier
      .getTableName
    (carbonTable, tableName)
  }

  /**
   * Below method will be used to get the query columns from plan
   *
   * @param groupByExpression
   * group by expression
   * @param aggregateExpressions
   * aggregate expression
   * @param carbonTable
   * parent carbon table
   * @param tableName
   * parent table name
   * @param set
   * list of attributes
   * @return plan is valid
   */
  def extractQueryColumnsFromAggExpression(groupByExpression: Seq[Expression],
      aggregateExpressions: Seq[NamedExpression],
      carbonTable: CarbonTable, tableName: String,
      set: scala.collection.mutable.HashSet[QueryColumn]): Boolean = {
    aggregateExpressions.map {
      case attr: AttributeReference =>
        set += getQueryColumn(attr.name,
          carbonTable,
          tableName)
      case Alias(attr: AttributeReference, _) =>
        set += getQueryColumn(attr.name,
          carbonTable,
          tableName);
      case Alias(attr: AggregateExpression, _) =>
        if (attr.isDistinct) {
          return false
        }
        val queryColumn = validateAggregateFunctionAndGetFields(carbonTable,
          attr.aggregateFunction,
          tableName)
        if (queryColumn.nonEmpty) {
          set ++= queryColumn
        } else {
          return false
        }
      case Alias(expression: Expression, _) =>
        if (expression.isInstanceOf[ScalaUDF] &&
            expression.asInstanceOf[ScalaUDF].function.getClass.getName.equalsIgnoreCase(
              "org.apache.spark.sql.execution.command.timeseries.TimeseriesFunction") &&
            CarbonUtil.hasTimeSeriesDataMap(carbonTable)) {
          set += getQueryColumn(expression.asInstanceOf[ScalaUDF].children(0)
            .asInstanceOf[AttributeReference].name,
            carbonTable,
            tableName,
            timeseriesFunction = expression.asInstanceOf[ScalaUDF].children(1).asInstanceOf[Literal]
              .value.toString)
        } else {
          expression.transform {
            case attr: AttributeReference =>
              set += getQueryColumn(attr.name,
                carbonTable,
                tableName)
              attr
          }
        }
    }
    true
  }

  /**
   * Below method will be used to validate aggregate function and get the attribute information
   * which is applied on select query.
   * Currently sum, max, min, count, avg is supported
   * in case of any other aggregate function it will return empty sequence
   * In case of avg it will return two fields one for count
   * and other of sum of that column to support rollup
   *
   * @param carbonTable
   * parent table
   * @param aggFunctions
   * aggregation function
   * @param tableName
   * parent table name
   * @return list of fields
   */
  def validateAggregateFunctionAndGetFields(carbonTable: CarbonTable,
      aggFunctions: AggregateFunction,
      tableName: String
  ): Seq[QueryColumn] = {
    val changedDataType = true
    aggFunctions match {
      case sum@Sum(attr: AttributeReference) =>
        Seq(getQueryColumn(attr.name,
          carbonTable,
          tableName,
          sum.prettyName))
      case sum@Sum(MatchCast(attr: AttributeReference, changeDataType: DataType)) =>
        Seq(getQueryColumn(attr.name,
          carbonTable,
          tableName,
          sum.prettyName,
          changeDataType.typeName,
          changedDataType))
      case count@Count(Seq(attr: AttributeReference)) =>
        Seq(getQueryColumn(attr.name,
          carbonTable,
          tableName,
          count.prettyName))
      case min@Min(attr: AttributeReference) =>
        Seq(getQueryColumn(attr.name,
          carbonTable,
          tableName,
          min.prettyName))
      case min@Min(MatchCast(attr: AttributeReference, changeDataType: DataType)) =>
        Seq(getQueryColumn(attr.name,
          carbonTable,
          tableName,
          min.prettyName,
          changeDataType.typeName,
          changedDataType))
      case max@Max(attr: AttributeReference) =>
        Seq(getQueryColumn(attr.name,
          carbonTable,
          tableName,
          max.prettyName))
      case max@Max(MatchCast(attr: AttributeReference, changeDataType: DataType)) =>
        Seq(getQueryColumn(attr.name,
          carbonTable,
          tableName,
          max.prettyName,
          changeDataType.typeName,
          changedDataType))
      // in case of average need to return two columns
      // sum and count of the column to added during table creation to support rollup
      case Average(attr: AttributeReference) =>
        Seq(getQueryColumn(attr.name,
          carbonTable,
          tableName,
          "sum"
        ), getQueryColumn(attr.name,
          carbonTable,
          tableName,
          "count"
        ))
      // in case of average need to return two columns
      // sum and count of the column to added during table creation to support rollup
      case Average(MatchCast(attr: AttributeReference, changeDataType: DataType)) =>
        Seq(getQueryColumn(attr.name,
          carbonTable,
          tableName,
          "sum",
          changeDataType.typeName,
          changedDataType), getQueryColumn(attr.name,
          carbonTable,
          tableName,
          "count",
          changeDataType.typeName,
          changedDataType))
      case _ =>
        Seq.empty
    }
  }



  /**
   * Below method will be used to get the query column object which
   * will have details of the column and its property
   *
   * @param columnName
   * parent column name
   * @param carbonTable
   * parent carbon table
   * @param tableName
   * parent table name
   * @param aggFunction
   * aggregate function applied
   * @param dataType
   * data type of the column
   * @param isChangedDataType
   * is cast is applied on column
   * @param isFilterColumn
   * is filter is applied on column
   * @return query column
   */
  def getQueryColumn(columnName: String,
      carbonTable: CarbonTable,
      tableName: String,
      aggFunction: String = "",
      dataType: String = "",
      isChangedDataType: Boolean = false,
      isFilterColumn: Boolean = false,
      timeseriesFunction: String = ""): QueryColumn = {
    val columnSchema = carbonTable.getColumnByName(tableName, columnName.toLowerCase)
    if(null == columnSchema) {
      null
    } else {
      if (isChangedDataType) {
        new QueryColumn(columnSchema.getColumnSchema,
          columnSchema.getDataType.getName,
          aggFunction.toLowerCase,
          isFilterColumn,
          timeseriesFunction.toLowerCase)
      } else {
        new QueryColumn(columnSchema.getColumnSchema,
          CarbonScalaUtil.convertSparkToCarbonSchemaDataType(dataType),
          aggFunction.toLowerCase,
          isFilterColumn,
          timeseriesFunction.toLowerCase)
      }
    }
  }
}

object CarbonPreAggregateDataLoadingRules extends Rule[LogicalPlan] {

  override def apply(plan: LogicalPlan): LogicalPlan = {
    val validExpressionsMap = scala.collection.mutable.LinkedHashMap.empty[String, NamedExpression]
    plan transform {
      case aggregate@Aggregate(_, aExp, _) if validateAggregateExpressions(aExp) =>
        aExp.foreach {
          case alias: Alias =>
            validExpressionsMap ++= validateAggregateFunctionAndGetAlias(alias)
          case _: UnresolvedAlias =>
          case namedExpr: NamedExpression => validExpressionsMap.put(namedExpr.name, namedExpr)
        }
        aggregate.copy(aggregateExpressions = validExpressionsMap.values.toSeq)
      case plan: LogicalPlan => plan
    }
  }

    /**
     * This method will split the avg column into sum and count and will return a sequence of tuple
     * of unique name, alias
     *
     */
    private def validateAggregateFunctionAndGetAlias(alias: Alias): Seq[(String,
      NamedExpression)] = {
      alias match {
        case udf@Alias(_: ScalaUDF, name) =>
          Seq((name, udf))
        case alias@Alias(attrExpression: AggregateExpression, _) =>
          attrExpression.aggregateFunction match {
            case Sum(attr: AttributeReference) =>
              (attr.name + "_sum", alias) :: Nil
            case Sum(MatchCastExpression(attr: AttributeReference, _)) =>
              (attr.name + "_sum", alias) :: Nil
            case Count(Seq(attr: AttributeReference)) =>
              (attr.name + "_count", alias) :: Nil
            case Count(Seq(MatchCastExpression(attr: AttributeReference, _))) =>
              (attr.name + "_count", alias) :: Nil
            case Average(attr: AttributeReference) =>
              Seq((attr.name + "_sum", Alias(attrExpression.
                copy(aggregateFunction = Sum(attr),
                  resultId = NamedExpression.newExprId), attr.name + "_sum")()),
                (attr.name, Alias(attrExpression.
                  copy(aggregateFunction = Count(attr),
                    resultId = NamedExpression.newExprId), attr.name + "_count")()))
            case Average(cast@MatchCastExpression(attr: AttributeReference, _)) =>
              Seq((attr.name + "_sum", Alias(attrExpression.
                copy(aggregateFunction = Sum(cast),
                  resultId = NamedExpression.newExprId),
                attr.name + "_sum")()),
                (attr.name, Alias(attrExpression.
                  copy(aggregateFunction = Count(cast), resultId =
                    NamedExpression.newExprId), attr.name + "_count")()))
            case _ => Seq(("", alias))
          }

      }
    }

  /**
   * Called by PreAggregateLoadingRules to validate if plan is valid for applying rules or not.
   * If the plan has PreAggLoad i.e Loading UDF and does not have PreAgg i.e Query UDF then it is
   * valid.
   *
   * @param namedExpression
   * @return
   */
  private def validateAggregateExpressions(namedExpression: Seq[NamedExpression]): Boolean = {
    val filteredExpressions = namedExpression.filterNot(_.isInstanceOf[UnresolvedAlias])
    filteredExpressions.exists { expr =>
          !expr.name.equalsIgnoreCase("PreAgg") && expr.name.equalsIgnoreCase("preAggLoad")
      }
  }
}
