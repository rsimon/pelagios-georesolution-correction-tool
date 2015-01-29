package controllers.tools

import controllers.common.auth.{ Secure, Secured }
import java.net.URL
import models._
import models.content.{ GeoDocumentText, GeoDocumentTexts }
import play.api.db.slick._
import play.api.Logger
import play.api.libs.json.Json
import play.api.mvc.Controller

object TextAnnotationController extends Controller with Secured {
  
  private val UTF8 = "UTF-8"

  def showTextAnnotationUI(textId: Int) = protectedDBAction(Secure.REDIRECT_TO_LOGIN) { username => implicit session =>
    val gdocText = GeoDocumentTexts.findById(textId)
    if (gdocText.isDefined) {
      val plaintext = new String(gdocText.get.text, UTF8)
      val annotations = if (gdocText.get.gdocPartId.isDefined) {
          Annotations.findByGeoDocumentPart(gdocText.get.gdocPartId.get)
        } else {
          Annotations.findByGeoDocument(gdocText.get.gdocId)
        }
      
      val gdoc = GeoDocuments.findById(gdocText.get.gdocId)
      val gdocPart = gdocText.get.gdocPartId.flatMap(id => GeoDocumentParts.findById(id))
      val allTexts = gdoc.map(doc => GeoDocumentContent.findByGeoDocument(doc.id.get)).getOrElse(Seq.empty[(GeoDocumentText, Option[String])])
      val signOffs = SignOffs.findForGeoDocumentText(gdocText.get.id.get).map(_._1)
      val source = // Part source if available, doc source otherwise 
        if (gdocPart.flatMap(_.source).isDefined)
          gdocPart.flatMap(_.source)
        else
          gdoc.flatMap(_.source)
      
      val html = buildHTML(plaintext, annotations)
      
      Ok(views.html.textAnnotation(
        gdocText,
        gdoc,
        gdocPart,
        allTexts, 
        username, 
        html, 
        source,
        signOffs.contains(username),
        signOffs))
    } else {
      NotFound(Json.parse("{ \"success\": false, \"message\": \"Text not found\" }")) 
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
  
  /** CTS support - experimental!!!
    * 
    * CTS texts are fetched remotely from the specified URL. Plaintext is extracted by grabbing all paragraph nodes in 
    * the XML and stripping off the rest. Recogito does not keep a copy of the text in the system.
    * It only stores the annotations.
    */
  def showCTSTextAnnotationUI(ctsURI: String) = protectedDBAction(Secure.REDIRECT_TO_LOGIN) { username => implicit session =>      
    val plaintext = {
      val foo = scala.xml.XML.load(new URL(ctsURI))
      val paragraphs = foo \\ "p"
      paragraphs.map(_.text.trim + "\n").mkString("\n")
    }

    val annotations = Annotations.findBySource(ctsURI)
    val html = buildHTML(plaintext, annotations)
    
    Ok(views.html.textAnnotation(None, None, None, Seq.empty[(GeoDocumentText, Option[String])], username, html, Some(ctsURI), false, Seq.empty[String]))
  }
  
}