/*
 * Copyright (C) 2016 Language Technology Group and Interactive Graphics Systems Group, Technische Universität Darmstadt, Germany
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package controllers

import javax.inject.Inject

import controllers.network._
import play.api.Logger
import play.api.libs.json.Writes

import scala.collection.mutable
// to read files

// scalastyle:off

import model.EntityType
import model.faceted.search.{ FacetedSearch, Facets, NodeBucket }
import play.api.libs.json.Writes._
import play.api.libs.json.{ JsObject, Json }
import play.api.mvc.{ Action, Controller, Results }
import util.SessionUtils.currentDataset
import util.TimeRangeParser
import util.TupleWriters._
//import scalikejdbc._

import scalikejdbc._

import scala.collection.mutable

/*
    This class encapsulates all functionality for the
    network graph.
*/
class NetworkController @Inject extends Controller {

  // TODO: fetch entity types from backend API

  var GuindanceMap: mutable.HashMap[String, GraphGuidance] = new mutable.HashMap[String, GraphGuidance]()
  var IterMap: mutable.HashMap[String, GuidanceIterator] = new mutable.HashMap[String, GuidanceIterator]()

  /**
   * the strings for different types
   * !! These are database specific !!
   */
  val locationIdentifier = "LOC"
  val orgIdentifier = "ORG"
  val personIdentifier = "PER"
  val miscIdentifier = "MISC"

  /**
   * This constant is multiplied with the amount of requested nodes in the getgraphdata method
   * to specify the returned relationships, e.g. if the amount is 10 and the multiplier is 1.5,
   * 10 nodes with 15 links between them are returned.
   */
  val relationshipMultiplier = 1.5
  /**
   * This constant is used when loading an ego network and specifies how many relationships
   * between the neighbors of the ego node are shown (i.e. number of links excluding the links
   * of the ego node).
   */
  val neighborRelCount = 5

  /**
   * If leastOrMostFrequent == 0:
   * Returns entities with the highest frequency and their relationships with
   * frequencies in the intervall ["minEdgeFreq", "maxEdgeFreq"].
   * If leastOrMostFrequent == 1:
   * Choose the entities with the lowest frequency.
   *
   * "amountOfType" tells how many nodes of which type shall be selected for the graph.
   * amountOfType[0] = contries/cities, amountOfType[1] = organizations,
   * amountOfType[2] = persons, amountOfType[3] = miscellaneous.
   */
  def getGraphData(leastOrMostFrequent: Int, amountOfType: List[Int], minEdgeFreq: Int, maxEdgeFreq: Int)(implicit session: DBSession = AutoSession) = Action {
    // a list of tuples of id, name, frequency and type (an "entity")
    var entities: List[(Long, String, Int, String)] = List()
    // a list of tuples of id, source, target and frequency (a "relation")
    var relations: List[(Long, Long, Long, Int)] = List()

    val sorting = if (leastOrMostFrequent == 0) sqls"desc" else sqls"asc"

    val locationCount = amountOfType.head
    val orgCount = amountOfType(1)
    val personCount = amountOfType(2)
    val miscCount = amountOfType(3)
    var amount = 0
    for (a <- amountOfType)
      amount += a

    // get locationCount of type location
    entities = sql"""(SELECT id, name, type, frequency
          FROM entity
          WHERE type = ${locationIdentifier}
          ORDER BY frequency ${sorting}
          LIMIT ${locationCount})
          UNION
          (SELECT id, name, type, frequency
          FROM entity
          WHERE type = ${orgIdentifier}
          ORDER BY frequency ${sorting}
          LIMIT ${orgCount})
          UNION
          (SELECT id, name, type, frequency
          FROM entity
          WHERE type = ${personIdentifier}
          ORDER BY frequency ${sorting}
          LIMIT ${personCount})
          UNION
          (SELECT id, name, type, frequency
          FROM entity
          WHERE type = ${miscIdentifier}
          ORDER BY frequency ${sorting}
          LIMIT ${miscCount})"""
      .map(rs => (rs.long("id"), rs.string("name"), rs.int("frequency"), rs.string("type")))
      .list()
      .apply()

    relations = sql"""SELECT DISTINCT ON (id, frequency) id, entity1, entity2, frequency
        FROM relationship
        WHERE entity1 IN (${entities.map(_._1)})
        AND entity2 IN (${entities.map(_._1)})
        AND frequency >= ${minEdgeFreq}
        AND frequency <= ${maxEdgeFreq}
        ORDER BY frequency ${sorting}
        LIMIT ${amount * relationshipMultiplier}"""
      .map(rs => (rs.long("id"), rs.long("entity1"), rs.long("entity2"), rs.int("frequency")))
      .list()
      .apply()

    val result = new JsObject(Map(("nodes", Json.toJson(entities)), ("links", Json.toJson(relations))))
    Ok(Json.toJson(result)).as("application/json")
  }

  /**
   * Returns the assosciated Id with the given name
   *
   * @param name
   * @return
   */
  def getIdsByName(name: String) = Action { implicit request =>
    Ok(Json.obj("ids" -> model.Entity.fromDBName(currentDataset).getByName(name).map(_.id))).as("application/json")
  }

  /**
   *
   * @param entities list of entity id's you want relations for
   * @param minEdgeFreq minimun Edge Frequency
   * @param maxEdgeFreq maximum Edge Frequency
   * @return
   */
  def getRelations(entities: List[Long], minEdgeFreq: Int, maxEdgeFreq: Int)(implicit session: DBSession = AutoSession) = Action {
    if (entities.nonEmpty) {
      val relations =
        sql"""SELECT DISTINCT ON (id, frequency) id, entity1, entity2, frequency
        FROM relationship
        WHERE entity1 IN (${entities})
        AND entity2 IN (${entities})
        AND frequency >= ${minEdgeFreq}
        AND frequency <= ${maxEdgeFreq}
        ORDER BY frequency DESC
        LIMIT 100"""
          .map(rs => (rs.long("id"), rs.long("entity1"), rs.long("entity2"), rs.int("frequency")))
          .list()
          .apply()

      Ok(Json.toJson(relations)).as("application/json")
    } else {
      Results.Ok(Json.toJson(List[JsObject]())).as("application/json")
    }
  }

  def induceSubgraph(
    fullText: List[String],
    generic: Map[String, List[String]],
    entities: List[Long],
    timeRange: String,
    size: Int,
    filter: List[Long]
  ) = Action { implicit request =>
    val times = TimeRangeParser.parseTimeRange(timeRange)
    val facets = Facets(fullText, generic, entities, times.from, times.to)
    var newSize = size
    if (filter.nonEmpty) newSize = filter.length
    val res = FacetedSearch.fromIndexName(currentDataset).induceSubgraph(facets, newSize)
    val subgraphEntities = res._1.map {
      case NodeBucket(id, count) => Json.obj("id" -> id, "count" -> count)
      case _ => Json.obj()
    }

    Ok(Json.toJson(Json.obj("entities" -> subgraphEntities, "relations" -> res._2))).as("application/json")
  }

  /**
   * deletes an entity from the graph by its id
   *
   * @param id the id of the entity to delete
   * @return if the deletion succeeded
   */
  def deleteEntityById(id: Long) = Action { implicit request =>
    Ok(Json.obj("result" -> model.Entity.fromDBName(currentDataset).delete(id))).as("application/json")
  }

  /**
   * merge all entities into one entity represented by the focalId
   *
   * @param focalid the entity to merge into
   * @param ids     the ids of the entities which are duplicates of
   *                the focal entity
   * @return if the merging succeeded
   */
  def mergeEntitiesById(focalid: Int, ids: List[Long]) = Action { implicit request =>
    Ok(Json.obj("result" -> model.Entity.fromDBName(currentDataset).merge(focalid, ids))).as("application/json")
  }

  /**
   * change the entity name by a new name of the given Entity
   *
   * @param id      the id of the entity to change
   * @param newName the new name of the entity
   * @return if the change succeeded
   */
  def changeEntityNameById(id: Long, newName: String) = Action { implicit request =>
    Ok(Json.obj("result" -> model.Entity.fromDBName(currentDataset).changeName(id, newName))).as("application/json")
  }

  /**
   * change the entity type by a new type
   *
   * @param id      the id of the entity to change
   * @param newType the new type of the entity
   * @return if the change succeeded
   */
  def changeEntityTypeById(id: Long, newType: String) = Action { implicit request =>
    Ok(Json.obj("result" -> model.Entity.fromDBName(currentDataset).changeType(id, EntityType.withName(newType)))).as("application/json")
  }

  // scalastyle:off
  /**
   * Returns the nodes and edges of the ego network of the node with id "id".
   * Which and how many nodes and edges are to be selected is defined by the
   * parameters "amountOfType" and "existingNodes".
   * If leastOrMostFrequent == 0 it is tried to get those with the highest frequency.
   * If leastOrMostFrequent == 1 it is tried to get those with the lowest frequency.
   *
   * "amountOfType" tells how many nodes of which type shall be selected for the ego
   * network. amountOfType[0] = contries/cities, amountOfType[1] = organizations,
   * amountOfType[2] = persons, amountOfType[3] = miscellaneous.
   *
   * "existingNodes" the ids of the nodes that are already in the ego network.
   */
  def getEgoNetworkData(leastOrMostFrequent: Int, id: Long, amountOfType: List[Int], existingNodes: List[Long])(implicit session: DBSession = AutoSession) = Action {
    // a list of tuples of id, name, frequency and type (an "entity")
    var entities: List[(Long, String, Int, String)] = List()
    // a list of tuples of id, source, target and frequency (a "relation")
    var relations: List[(Long, Long, Long, Int)] = List()

    val sorting = if (leastOrMostFrequent == 0) sqls"DESC" else sqls"ASC"

    val locationCount = amountOfType.head
    val orgCount = amountOfType(1)
    val personCount = amountOfType(2)
    val miscCount = amountOfType(3)

    val existingNodesForSql = if (existingNodes.isEmpty) List(-1) else existingNodes
    // get locationCount of type location
    relations = sql"""(SELECT relationship.id, entity1, entity2, relationship.frequency
                                        FROM relationship, entity
                                        WHERE entity1 = ${id}
                                        AND entity2 NOT IN (${existingNodesForSql})
                                        AND entity2 = entity.id
                                        AND type = ${locationIdentifier}
                                        ORDER BY relationship.frequency ${sorting}
                                        LIMIT ${locationCount})

                                        UNION
          (SELECT relationship.id, entity1, entity2, relationship.frequency
                                                  FROM relationship, entity
                                                  WHERE entity1 = ${id}
                                                  AND entity2 NOT IN (${existingNodesForSql})
                                                  AND entity2 = entity.id
                                                  AND type = ${orgIdentifier}
                                                  ORDER BY relationship.frequency ${sorting}
                                                  LIMIT ${orgCount})
          UNION
          (SELECT relationship.id, entity1, entity2, relationship.frequency
                                                  FROM relationship, entity
                                                  WHERE entity1 = ${id}
                                                  AND entity2 NOT IN (${existingNodesForSql})
                                                  AND entity2 = entity.id
                                                  AND type = ${personIdentifier}
                                                  ORDER BY relationship.frequency ${sorting}
                                                  LIMIT ${personCount})
                                                                                          UNION
          (SELECT relationship.id, entity1, entity2, relationship.frequency
                                                  FROM relationship, entity
                                                  WHERE entity1 = ${id}
                                                  AND entity2 NOT IN (${existingNodesForSql})
                                                  AND entity2 = entity.id
                                                  AND type = ${miscIdentifier}
                                                  ORDER BY relationship.frequency ${sorting}
                                                  LIMIT ${miscCount})"""
      .map(rs => (rs.long("id"), rs.long("entity1"), rs.long("entity2"), rs.int("frequency")))
      .list()
      .apply()

    // IF the relation list IS NOT empty
    if (relations.nonEmpty) {
      val relationConcat = relations.map(_._2) ++ relations.map(_._3)
      entities = sql"""SELECT id, name, type, frequency
                           FROM entity
                           WHERE id IN (${relationConcat})"""
        .map(rs => (rs.long("id"), rs.string("name"), rs.int("frequency"), rs.string("type")))
        .list()
        .apply()

    }
    // Get the neighborRelCount most/least relevant relations between neighbors of the node with the id "id".
    relations = List.concat(relations, getNeighborRelations(sorting, entities, neighborRelCount, id))

    val result = new JsObject(Map(("nodes", Json.toJson(entities)), ("links", Json.toJson(relations))))

    Ok(Json.toJson(result)).as("application/json")
  }
  // scalastyle:off

  /**
   * Returns a list with "amount" relations between neighbors of the node with the id "id".
   */
  private def getNeighborRelations(
    sorting: SQLSyntax,
    entities: List[(Long, String, Int, String)],
    amount: Int,
    id: Long
  )(implicit session: DBSession = AutoSession): List[(Long, Long, Long, Int)] = {
    if (entities.nonEmpty) {
      sql"""SELECT DISTINCT ON(id, frequency) id, entity1, entity2, frequency
          FROM relationship
          WHERE entity1 IN (${entities.map(_._1)})
          AND entity2 IN (${entities.map(_._1)})
          ORDER BY frequency ${sorting}
          LIMIT ${amount}"""
        .map(rs => (rs.long("id"), rs.long("entity1"), rs.long("entity2"), rs.int("frequency")))
        .list
        .apply()
    } else {
      List()
    }
  }

  /**
   *
   * @param focusId anfokussierter Knoten
   * @param edgeAmount Gesamtanzahl der Kanten im Subgraph
   * @param epn maximale Anzahl der Kanten pro Knoten
   * @param uiString User Interesse an verschiedenen Kantentypen (als String gespeicherte Matrix die mit , und ; getrennt ist=
   * @param useOldEdges true: alte DoI-Werte fliessen in Berechnung der neuen DoI-Werte ein
   * @param sessionId SessionId
   * @return sendet den gebildeten Supgraph in Form von Kanten+Knoten an den Benutzer
   */
  def getGuidanceNodes(focusId: Long, edgeAmount: Int, epn: Int, uiString: String, useOldEdges: Boolean, sessionId: String) = Action {

    val uiMatrix: Array[Array[Int]] = uiString.split(";").map(_.split(",").map(_.toInt))
    implicit val gg = GuindanceMap.getOrElseUpdate(sessionId, new GraphGuidance)
    val ggIter = gg.getGuidance(focusId, edgeAmount, epn, uiMatrix, useOldEdges)
    val (edges, nodes) = ggIter.take(edgeAmount).toList.unzip
    val result = new JsObject(Map(("nodes", Json.toJson(nodes.flatten /*entfernt die leeren Options*/ ++ NodeFactory.createNodes(List(focusId), 0, 0))), ("links", Json.toJson(edges))))
    IterMap += (sessionId -> ggIter)
    Logger.debug(result.toString())
    Ok(result).as("application/json")
  }

  def getAdditionalEdges(nodeId: Long, amount: Int, sessionId: String) = Action {
    val (eList, nList) = IterMap(sessionId).getMoreEdges(nodeId, amount)
    val result = new JsObject(Map(("nodes", Json.toJson(nList)), ("links", Json.toJson(eList))))
    Logger.debug("add Eddges for" + nodeId)
    Logger.debug(result.toString())
    Ok(result).as("application/json")
  }

  implicit val NodeWrite = new Writes[Node] {
    override def writes(n: Node) = Json.obj(
      "id" -> n.getId,
      "name" -> n.getName,
      "docOcc" -> n.getDocOcc,
      "type" -> n.getCategory,
      "edges" -> n.getRelevantNodes.map(rn => Json.obj(
        "id" -> rn.getId,
        "name" -> rn.getName,
        "type" -> rn.getCategory
      )).toList
    )
  }

  implicit val EdgeWrite = new Writes[Edge] {
    def writes(e: Edge) = Json.obj(
      "id" -> 0,
      "sourceNode" -> e.getNodes._1.getId,
      "targetNode" -> e.getNodes._2.getId,
      "docOcc" -> e.getDocOcc,
      "uiLevel" -> e.getUiLevel
    )
  }

}

