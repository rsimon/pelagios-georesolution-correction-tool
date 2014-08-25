package controllers

import models._
import models.content._
import models.stats.CompletionStats
import play.api.db.slick._
import play.api.Play.current
import play.api.libs.json.Json
import play.api.mvc.{ Action, Controller }
import play.api.Logger

/** Encapsulates the information shown in one row of the landing page's document index **/
case class DocumentIndexRow(doc: GeoDocument, stats: CompletionStats, firstText: Option[Int], firstImage: Option[Int])

/** Main application entrypoint.
  *
  * @author Rainer Simon <rainer.simon@ait.ac.at>
  */
object ApplicationController extends Controller with Secured with CTSClient {
  
  private val UTF8 = "UTF-8"
    
  /** Returns the index page for logged-in users **/
  def index(collection: Option[String]) = DBAction { implicit rs =>    
    if (collection.isEmpty) {
      // If no collection is selected, redirect to the first in the list
      val allCollections = CollectionMemberships.listCollections :+ "other"
      Redirect(routes.ApplicationController.index(Some(allCollections.head.toLowerCase)))
    
    } else {
      // Helper function to collapse multiple language versions of the same document into one 
      def foldLanguageVersions(docs: Seq[DocumentIndexRow]): Seq[(Option[String], Seq[DocumentIndexRow])] = {
        val groupedByExtID = docs.filter(_.doc.externalWorkID.isDefined).groupBy(_.doc.externalWorkID)
        
        // Creates a list of [Ext. Work ID -> Documents] mappings
        docs.foldLeft(Seq.empty[(Option[String], Seq[DocumentIndexRow])])((collapsedList, document) => {
          if (document.doc.externalWorkID.isEmpty) {
            // No external work ID means we don't group this doc with other docs
            collapsedList :+ (None, Seq(document))
          } else {
            val workIDsInList = collapsedList.filter(_._1.isDefined).map(_._1.get)
            if (!workIDsInList.contains(document.doc.externalWorkID.get))
              // This is a doc that needs grouping, and it's not in the list yet
              collapsedList :+ (document.doc.externalWorkID, groupedByExtID.get(document.doc.externalWorkID).get)
            else
              // This doc is already in the list
              collapsedList
          }
        })
      }
      
      // IDs of all documents NOT assigned to a collection
      val unassigned = CollectionMemberships.getUnassignedGeoDocuments
      
      // Documents for the selected collection, with content IDs
      val gdocsWithcontent: Seq[(GeoDocument, Seq[Int], Seq[Int])] = {
        if (collection.get.equalsIgnoreCase("other"))
          GeoDocuments.findByIdsWithContent(unassigned)
        else
          GeoDocuments.findByIdsWithContent(CollectionMemberships.getGeoDocumentsInCollection(collection.get)) 
        }
        .sortBy(t => (t._1.date, t._1.author, t._1.title))
        
      // Get stats for each document
      val stats: Map[Int, CompletionStats] = 
        Annotations.getCompletionStats(gdocsWithcontent.map(_._1.id.get))

      // Merge docs, stats & first content IDs to form the 'index row'
      val indexRows = gdocsWithcontent.map { case (gdoc, texts, images) => {
        DocumentIndexRow(gdoc, stats.get(gdoc.id.get).getOrElse(CompletionStats.empty), texts.headOption, images.headOption)
      }}
                  
      // The information require for the collection selection widget
      val docsPerCollection: Seq[(String, Int)] = 
        CollectionMemberships.listCollections.map(collection =>
          (collection, CollectionMemberships.countGeoDocumentsInCollection(collection))) ++
          { if (unassigned.size > 0) Seq(("Other", unassigned.size)) else Seq.empty[(String, Int)] }
      
      val groupedDocs = foldLanguageVersions(indexRows)
            
      // Populate the correct template, depending on login state
      if (currentUser.isDefined && isAuthorized)      
        Ok(views.html.index(groupedDocs, docsPerCollection, collection.get, EditHistory.listHighscores(5), currentUser.get))
      else 
        Ok(views.html.public_index(groupedDocs, docsPerCollection, EditHistory.listHighscores(5), collection.get))
    }
  }
   
  /** Shows the 'public map' for the specified document.
    *  
    * @param doc the document ID 
    */  
  def showMap(doc: Int) = DBAction { implicit rs =>
    val document = GeoDocuments.findById(doc)
    if (document.isDefined)
      Ok(views.html.public_map(document.get))
    else
      NotFound
  }
    
  /** Shows the text annotation UI for the specified text.
    * 
    * @param text the internal ID of the text in the DB 
    */
  def showTextAnnotationUI(text: Option[Int], ctsURI: Option[String]) = protectedDBAction(Secure.REDIRECT_TO_LOGIN) { username => implicit request =>   
    // Warning: temporary (d'oh) hack for supporting CTS alongside stored texts
    val (someGDocText, somePlaintext, someAnnotations) = if (ctsURI.isDefined) {
      val annotations = Annotations.findBySource(ctsURI.get)
      (None, Some(getPlaintext(ctsURI.get)), Some(annotations))
    } else {
      val gdocText = GeoDocumentTexts.findById(text.get)
      val a = gdocText.map(txt => {
        if (gdocText.get.gdocPartId.isDefined) {
          Annotations.findByGeoDocumentPart(gdocText.get.gdocPartId.get)
        } else {
          Annotations.findByGeoDocument(gdocText.get.gdocId)
        }
      })
      (gdocText, gdocText.map(txt => new String(txt.text, UTF8)), a)
    }
    
    if (somePlaintext.isDefined && someAnnotations.isDefined) {
      val gdoc = someGDocText.map(text => GeoDocuments.findById(text.gdocId)).flatten
      val gdocPart = someGDocText.flatMap(text => text.gdocPartId.map(id => GeoDocumentParts.findById(id))).flatten
          
      val texts = gdoc.map(doc => GeoDocumentContent.findByGeoDocument(doc.id.get)).getOrElse(Seq.empty[(GeoDocumentText, Option[String])])     
      val html = buildHTML(somePlaintext.get, someAnnotations.get)
      
      val signOffs = someGDocText.map(text => SignOffs.findForGeoDocumentText(text.id.get).map(_._1))
        .getOrElse(Seq.empty[String])
        
      Ok(views.html.annotation_text(
          gdoc,
          gdoc.map(gdoc => GeoDocumentContent.findByGeoDocument(gdoc.id.get)).getOrElse(Seq.empty[(GeoDocumentText, Option[String])]), 
          username, 
          gdocPart, 
          html, 
          ctsURI,
          signOffs.contains(username),
          signOffs.size))
    } else {
      NotFound(Json.parse("{ \"success\": false, \"message\": \"Annotation not found\" }")) 
    }
  }

  private def buildHTML(plaintext: String, annotations: Seq[Annotation])(implicit session: Session): String = {
    val ranges = annotations.foldLeft(("", 0)) { case ((markup, beginIndex), annotation) => {
      if (annotation.status == AnnotationStatus.FALSE_DETECTION) {
        (markup, beginIndex)
      } else {
        // Use corrections if they exist, or Geoparser results otherwise
        val toponym = if (annotation.correctedToponym.isDefined) annotation.correctedToponym else annotation.toponym
        val offset = if (annotation.correctedOffset.isDefined) annotation.correctedOffset else annotation.offset 
        val url = if (annotation.correctedGazetteerURI.isDefined && !annotation.correctedGazetteerURI.get.trim.isEmpty) 
            annotation.correctedGazetteerURI
          else annotation.gazetteerURI

        if (offset.isDefined && offset.get < beginIndex)
          debugTextAnnotationUI(annotation)
          
        val cssClassA = annotation.status match {
          case AnnotationStatus.VERIFIED => "annotation verified"
          case AnnotationStatus.IGNORE => "annotation ignore"
          case AnnotationStatus.NO_SUITABLE_MATCH => "annotation not-identifyable"
          case AnnotationStatus.AMBIGUOUS => "annotation not-identifyable"
          case AnnotationStatus.MULTIPLE => "annotation not-identifyable"
          case AnnotationStatus.NOT_IDENTIFYABLE => "annotation not-identifyable"
          case _ => "annotation" 
        }
          
        val cssClassB = if (annotation.correctedToponym.isDefined) " manual" else " automatic"
   
        val title = "#" + annotation.uuid + " " +
            AnnotationStatus.screenName(annotation.status) + " (" +
          { if (annotation.correctedToponym.isDefined) "Manual Correction" else "Automatic Match" } +
            ")"
            
        if (toponym.isDefined && offset.isDefined) {
          val nextSegment = escapePlaintext(plaintext.substring(beginIndex, offset.get)) +
            "<span data-id=\"" + annotation.uuid + "\" class=\"" + cssClassA + cssClassB + "\" title=\"" + title + "\">" + escapePlaintext(toponym.get) + "</span>"
              
          (markup + nextSegment, offset.get + toponym.get.size)
        } else {
          (markup, beginIndex)
        }
      }
    }}
 
    (ranges._1 + escapePlaintext(plaintext.substring(ranges._2))).replace("\n", "<br/>") 
  }
  
  private def escapePlaintext(segment: String): String = {
    // Should cover most cases (?) - otherwise switch to Apache Commons StringEscapeUtils
    segment
      .replace("<", "&lt;")
      .replace(">", "&gt;")
  }
  
  /** Helper method that generates detailed debug output for overlapping annotations.
    * 
    * @param annotation the offending annotation
    */
  private def debugTextAnnotationUI(annotation: Annotation)(implicit s: Session) = {
    val toponym = if (annotation.correctedToponym.isDefined) annotation.correctedToponym else annotation.toponym
    Logger.error("Offending annotation: #" + annotation.uuid + " - " + annotation)
    Annotations.getOverlappingAnnotations(annotation).foreach(a => Logger.error("Overlaps with: #" + a.uuid))
  }
  
  def showImageAnnotationUI(imageId: Int) = protectedDBAction(Secure.REDIRECT_TO_LOGIN) { username => implicit request =>
    val gdocImage = GeoDocumentImages.findById(imageId)
    if (gdocImage.isDefined) {
      val gdoc = GeoDocuments.findById(gdocImage.get.gdocId)
      Ok(views.html.annotation_image(gdoc.get, gdocImage.get, username))
    } else {
      NotFound
    }
  }

  /** Shows the map-based georesolution correction UI for the specified document.
    *
    * @param doc the document ID 
    */
  def showGeoResolutionUI(docId: Int) = protectedDBAction(Secure.REDIRECT_TO_LOGIN) { username => implicit session => 
    val doc = GeoDocuments.findById(docId)
    if (doc.isDefined)
      Ok(views.html.georesolution(doc.get, GeoDocumentContent.findByGeoDocument(docId), username))
    else
      NotFound
  }
  
  /** Shows the tutorial page **/
  def showDocumentation() = Action {
    Redirect("/recogito/static/documentation/index.html")
  } 

}
