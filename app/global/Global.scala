package global

import akka.actor.Cancellable
import index.PlaceIndex
import java.io.{ File, FileInputStream }
import java.sql.Timestamp
import java.util.zip.GZIPInputStream
import models._
import models.content._
import org.openrdf.rio.RDFFormat
import org.pelagios.Scalagios
import org.pelagios.api.gazetteer.Place
import org.pelagios.api.gazetteer.patch.PatchConfig
import play.api.{ Application, GlobalSettings, Logger, Play }
import play.api.mvc.RequestHeader
import play.api.db.slick._
import play.api.Play.current
import scala.slick.jdbc.meta.MTable
import org.pelagios.api.gazetteer.patch.PatchStrategy

/** Play Global object **/
object Global extends GlobalSettings {
  
  private val GAZETTEER_DIR = "gazetteer"
  
  private val INDEX_DIR = "index"
    
  private var statsDemon: Option[Cancellable] = None
 
  lazy val index = {
    val idx = PlaceIndex.open(INDEX_DIR)
    if (idx.isEmpty) {
      Logger.info("Building new index")
      
      val dumps = Play.current.configuration.getString("recogito.gazetteers")
        .map(_.split(",").toSeq).getOrElse(Seq.empty[String]).map(_.trim)
        
      dumps.foreach(f => {
        Logger.info("Loading gazetteer dump: " + f)
        val (is, filename) = if (f.endsWith(".gz"))
            (new GZIPInputStream(new FileInputStream(new File(GAZETTEER_DIR, f))), f.substring(0, f.lastIndexOf('.')))
          else
            (new FileInputStream(new File(GAZETTEER_DIR, f)), f)
         
        idx.addPlaceStream(is, filename)
        idx.refresh()
      })
      
      Logger.info("Index complete")
      
      val patches = Play.current.configuration.getString("recogito.gazetteer.patches")
        .map(_.split(",").toSeq).getOrElse(Seq.empty[String]).map(_.trim)
       
      // Patching strategy is to REPLACE geometries, but APPEND names
      val patchConfig = PatchConfig()
        .geometry(PatchStrategy.REPLACE)
        .names(PatchStrategy.APPEND)
        .propagate(true) // Patches should propagate to linke places, too
      
      patches.foreach(patch => {
        Logger.info("Applying gazetteer patch file " + patch)
        idx.applyPatch(new File(GAZETTEER_DIR, patch), patchConfig)
        idx.refresh()
        Logger.info("Done.")
      })
    }
    idx
  }
  
  lazy val uploadDir = {
    val path = Play.current.configuration.getString("recogito.image.dir").getOrElse("public/uploads")
    val dir = new File(path)
    if (!dir.exists)
      dir.mkdir
    dir
  }
  
  lazy private val uploadBasePath = {
    val path = Play.current.configuration.getString("recogito.image.baseurl").getOrElse("/recogito/static/uploads/")
    if (path.endsWith("/"))
      path
    else
      path + "/"
  }
  
  def uploadBaseURL()(implicit request: RequestHeader) = {
    if (uploadBasePath.startsWith("http")) {
      uploadBasePath
    } else { 
      "http://" + request.host + uploadBasePath
    }
  }

  override def onStart(app: Application): Unit = {
    // Create DB tables if they don't exist
    DB.withSession { implicit session: Session =>
      if (MTable.getTables("users").list.isEmpty) {
        Logger.info("Users DB table does not exist - creating")
        Users.create
         
        // Create default admin user        
        val salt = Users.randomSalt
        Users.insert(User("admin", Users.computeHash(salt + "admin"), salt, new Timestamp(System.currentTimeMillis), "*", true))
      }
       
      if (MTable.getTables("gdocuments").list.isEmpty) {
        Logger.info("GeoDocuments DB table does not exist - creating")
        GeoDocuments.create
      }
      
      if (MTable.getTables("gdocument_parts").list.isEmpty) {
        Logger.info("GeoDocumentParts DB table does not exist - creating")
        GeoDocumentParts.create
      }
      
      if (MTable.getTables("gdocument_texts").list.isEmpty) {
        Logger.info("GeoDocumentTexts DB table does not exist - creating")
        GeoDocumentTexts.create
      }
      
      if (MTable.getTables("gdocument_images").list.isEmpty) {
        Logger.info("GeoDocumentImages DB table does not exist - creating")
        GeoDocumentImages.create
      }
      
      if (MTable.getTables("collection_memberships").list.isEmpty) {
        Logger.info("CollectionMemberships DB table does not exist - creating")
        CollectionMemberships.create
      }
      
      if (MTable.getTables("signoffs").list.isEmpty) {
        Logger.info("SignOffs DB table does not exist - creating")
        SignOffs.create
      }
      
      if (MTable.getTables("annotations").list.isEmpty) {
        Logger.info("Annotations DB table does not exist - creating")
        Annotations.create
      } 
       
      if (MTable.getTables("edit_history").list.isEmpty) {
        Logger.info("EditHistory DB table does not exist - creating")
        EditHistory.create
      }
        
      if (MTable.getTables("global_stats_history").list.isEmpty) {
        Logger.info("GlobalStatsHistory DB table does not exist - creating")
        GlobalStatsHistory.create
      }
      
      if (MTable.getTables("collection_stats_history").list.isEmpty) {
        Logger.info("CollectionStatsHistory DB table does not exist - creating")
        CollectionStatsHistory.create
      }
      
      // Clean up inconsistencies from the collections table
      val conflicts = CollectionMemberships.repairCollectionMemperships()
      if (conflicts.size > 0)
        Logger.warn("Removed " + conflicts.size + " broken collection associations: " + conflicts.mkString(", "))
    }
    
    // Periodic stats logging
    val runStatsDemon = Play.current.configuration.getBoolean("recogito.stats.enabled").getOrElse(false)
    if (runStatsDemon) {
      Logger.info("Starting stats logging background actor")
      statsDemon = Some(StatsDemon.start())
    }
  }  
  
  override def onStop(app: Application): Unit = {
    index.close()
    
    if (statsDemon.isDefined) {
      Logger.info("Shutting down stats logging background actor")
      statsDemon.get.cancel
      statsDemon = None
    }
  }

}
