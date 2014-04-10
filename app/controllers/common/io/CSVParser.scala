package controllers.common.io

import java.io.Reader
import java.util.UUID
import java.util.regex.Pattern
import models._
import play.api.db.slick._
import scala.io.Source
import java.sql.Timestamp
import models.stats.AnnotationStats

/** Utility object to convert CSV input data to Annotation objects.
  * 
  * @author Rainer Simon <rainer.simon@ait.ac.at> 
  */
class CSVParser extends BaseParser {
  
  private val SEPARATOR = ";"
    
  private val SPLIT_REGEX = "(?<!\\\\)" + Pattern.quote(SEPARATOR)
  
  /** Parses annotations from an input CSV file.
    * 
    * Since in the data model, annotations cannot exist without a valid parent
    * document, it is required to specify a GeoDocument ID on import.
    * @param file the CSV file path
    * @param gdocId the database ID of the GeoDocument to import to
    */
  def parseAnnotations(file: String, gdocId: Int)(implicit s: Session): Seq[Annotation] =
    parseAnnotations(Source.fromFile(file), gdocId)
    
  /** Parses annotations from a a scala.io.Source containing CSV data.
    * 
    * @param source the CSV source
    * @param gdocId the database ID of the GeoDocument to import to
    */
  def parseAnnotations(source: Source, gdocId: Int)(implicit s: Session): Seq[Annotation] = {
    val data = source.getLines    
    val header = data.take(1).toSeq.head.split(SEPARATOR, -1).toSeq 
    
    val idxUUID = idx(header, "uuid")
    val idxGdocPart = idx(header, "gdoc_part")
    val idxStatus = idx(header, "status")
    val idxToponym = idx(header, "toponym")
    val idxOffset = idx(header, "offset")
    val idxGazetteerURI = idx(header, "gazetteer_uri")
    val idxCorrectedToponym = idx(header, "toponym_corrected")
    val idxCorrectedOffset = idx(header, "offset_corrected")
    val idxCorrectedGazetteerURI = idx(header, "gazetteer_uri_corrected")
    val idxTags = idx(header, "tags")
    val idxComment = idx(header, "comment")
    val idxSource = idx(header, "source")
    val idxSeeAlso = idx(header, "see_also")
    
    data.map(_.split(SPLIT_REGEX, -1)).map(implicit fields => {
      Annotation(
          idxUUID.map(idx => UUID.fromString(fields(idx))).getOrElse(Annotations.newUUID),
          Some(gdocId),
          idxGdocPart.map(idx => getPartIdForTitle(gdocId, fields(idx))).flatten,
          parseOptCol(idxStatus).map(AnnotationStatus.withName(_)).getOrElse(AnnotationStatus.NOT_VERIFIED),
          parseOptCol(idxToponym),
          parseOptCol(idxOffset).map(_.toInt),
          parseOptCol(idxGazetteerURI),
          parseOptCol(idxCorrectedToponym),
          parseOptCol(idxCorrectedOffset).map(_.toInt),
          parseOptCol(idxCorrectedGazetteerURI),
          parseOptCol(idxTags),
          parseOptCol(idxComment),
          parseOptCol(idxSource),
          parseOptCol(idxSeeAlso))
    }).toSeq
  }

  /** Parses user data from an input CSV file.
    * 
    * @param file the CSV file path
    */
  def parseUsers(file: String): Seq[User] = {
    val data = Source.fromFile(file).getLines    
    val header = data.take(1).toSeq.head.split(SEPARATOR, -1).toSeq 
    
    val idxUsername = idx(header, "username")
    val idxHash = idx(header, "hash")
    val idxSalt = idx(header, "salt")
    val idxEditableDocuments = idx(header, "editable_documents")
    val idxIsAdmin = idx(header, "is_admin")
    
    data.map(_.split(SPLIT_REGEX, -1)).map(fields => {
      // All fields must be there - it's ok to fail if not
      User(
        fields(idxUsername.get),
        fields(idxHash.get),
        fields(idxSalt.get),
        fields(idxEditableDocuments.get),
        fields(idxIsAdmin.get).toBoolean)
    }).toSeq
  }
  
  def parseEditHistory(file: String): Seq[EditEvent] = {
    val data = Source.fromFile(file).getLines
    val header = data.take(1).toSeq.head.split(SEPARATOR, -1).toSeq

    val idxAnnotationId = idx(header, "annotation_id")
    val idxUsername = idx(header, "username")
    val idxTimestamp = idx(header, "timestamp")
    val idxAnnotationBefore = idx(header, "annotation_before")
    val idxUpdatedToponym = idx(header, "updated_toponym")
    val idxUpdatedStatus = idx(header, "updated_status")
    val idxUpdatedURI = idx(header, "updated_uri")
    val idxUpdatedTags = idx(header, "updated_tags")
    val idxUpdatedComment = idx(header, "updated_comment")
    
    data.map(_.split(SPLIT_REGEX, -1)).map(implicit fields => {
      // All fields must be there - it's ok to fail if not
      EditEvent(None,
        UUID.fromString(fields(idxAnnotationId.get)),
        fields(idxUsername.get),
        new Timestamp(fields(idxTimestamp.get).toLong),
        parseOptCol(idxAnnotationBefore),
        parseOptCol(idxUpdatedToponym),
        parseOptCol(idxUpdatedStatus).map(AnnotationStatus.withName(_)),
        parseOptCol(idxUpdatedURI),
        parseOptCol(idxUpdatedTags),
        parseOptCol(idxUpdatedComment))
    }).toSeq
  }
  
  def parseStatsTimeline(file: String): Seq[StatsRecord] = {
    val data = Source.fromFile(file).getLines
    val header = data.take(1).toSeq.head.split(SEPARATOR, -1).toSeq

    val idxTimestamp = idx(header, "timestamp")
    val idxVerifiedToponyms = idx(header, "verified_toponyms")
    val idxUnverifiedToponyms = idx(header, "unverified_toponyms")
    val idxUnidentifiableToponyms = idx(header, "unidentifiable_toponyms")
    val idxTotalEdits = idx(header, "total_edits")
    
    data.map(_.split(SPLIT_REGEX, -1)).map(fields => {
      // All fields required - it's ok to fail if not
      StatsRecord(None,
          new Timestamp(fields(idxTimestamp.get).toLong),
          fields(idxVerifiedToponyms.get).toInt,
          fields(idxUnverifiedToponyms.get).toInt,
          fields(idxUnidentifiableToponyms.get).toInt,
          fields(idxTotalEdits.get).toInt)
    }).toSeq
  }
  
  /** Helper method to find the row index of a specific header label 
    *
    * @param header the CSV headers
    * @param label the label for which to find the position
    */
  private def idx(header: Seq[String], label: String): Option[Int] = {
    header.indexWhere(_.equalsIgnoreCase(label)) match {
      case -1 => None
      case idx => Some(idx)
    }
  }
  
  /** Helper method to turn optional fields to Option[String] **/
  private def parseOptCol(idx: Option[Int])(implicit fields: Array[String]): Option[String] = {
    if (idx.isDefined) {
      val string = fields(idx.get)
      if (string.trim.isEmpty) 
        None // The field is in the CSV, but the string is empty -> None 
      else
        Some(string) // Field is there & contains a string
    } else {
      // If the field is not in the CSV at all -> None
      None
    }
  }
  
}