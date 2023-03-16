import org.apache.spark.SparkContext
import org.apache.spark.SparkConf
import org.apache.spark.rdd.RDD

import java.io.PrintWriter
import scala.io.{BufferedSource, Source}
import scala.language.postfixOps
import scala.math.sqrt

val fileName: String = "/home/gabbo/University/Magistrale/scpproject/MusicReccomendation/src/main/resources/train_triplets_2k.txt"
//def in: BufferedSource = Source.fromFile(getClass.getClassLoader.getResource(fileName).getPath)
def in: BufferedSource = Source.fromFile(fileName)


val conf = new SparkConf().setAppName("MusicRecommendation").setMaster("local[*]")
val ctx = new SparkContext(conf)

def songsByUser() = {
  // map user1->[{songs listened by user1}], ..., userN->[{songs listened by userN}]
  val songsUsersMap = collection.mutable.Map[String, List[String]]()
  // all users in file
  val usersInFile = collection.mutable.Set[String]()
  // all songs in file
  val songsInFile = collection.mutable.Set[String]()
  // for each split line on "\t"
  for {
    line <- in.getLines().toList
  } yield line split "\t" match {
      case Array(u, s, _) => {
        // add user and song
        usersInFile add u
        songsInFile add s
        // update map with cases
        songsUsersMap.updateWith(u) {
          // if user is already in the map, add song to the list of listened songs
          case Some(list: List[String]) => Some(list :+ s)
          // else add song to a new list related to the user
          case None => Some(List(s))
        }
      }
    }
  (usersInFile.toSeq, songsInFile.toSeq, songsUsersMap.toMap)
}

// get data from file
val (users, songs, usersToSongsMap) = songsByUser()

def time[R](block: => R, operation: String = "unknown"): R = {
  // get start time
  val t0 = System.nanoTime()
  // execute code
  val result = block
  // get end time
  val t1 = System.nanoTime()
  // print elapsed time
  println(s"Elapsed time for $operation:\t" + (t1 - t0) / 1000000 + "ms")
  // return the result
  result
}

def getModel(rank: (String, String) => Double) = {
  for {
    u <- users
    s <- songs
  } yield {
    u -> (s, rank(u,s))   // this had an error
//    songs.map(s => u -> (s.mkString, rank(u, s.mkString)))
  }
}

def writeModelOnFile(model: IterableOnce[(String, (String, Double))], outputFileName: String = ""): Unit = {
  val out = new PrintWriter(getClass.getClassLoader.getResource(outputFileName).getPath)
  // we are printing to a file; therefore, parallelization would not improve performances
  model.iterator foreach (el => {
    out.write(s"${el._1}\t${el._2._1}\t${el._2._2}\n")
  })
  out.close()
}

def getUserBasedModel() = {
  // it calculates the cosine similarity between two users
  val cosineSimilarity = (user1: String, user2: String) => {
    // Here, parallelization does not improve performances (TODO: check)
    val numerator = songs.map(song =>
      // if both users listened to song return 1, else 0
      if (usersToSongsMap(user1).contains(song) && usersToSongsMap(user2).contains(song)) 1 else 0
    ).fold(0) { (acc, tup) => acc + tup }
    // usersToSongMap(user).length represents how many songs the user listened to
    val denominator = sqrt(usersToSongsMap(user1).length) * sqrt(usersToSongsMap(user2).length)
    if (denominator != 0) numerator / denominator else 0.0
  }

  def rank(user: String, song: String): Double = {
//    for {
//      u2 <- users
//      if u2 != user && usersToSongsMap(u2).contains(song)
//    } yield {
//      cosineSimilarity(user, u2)
//    }
    users.map(u => if (u != user && usersToSongsMap(u).contains(song)) cosineSimilarity(user, u) else 0) sum
  }


  // Calculate model
  val userBasedModel = time(getModel(rank), "(Distributed) user-based model")

  writeModelOnFile(userBasedModel, "models/userBasedModelD.txt")
//  userBasedModel.saveAsTextFile("~/University/Magistrale/scpproject/UBM")
  userBasedModel
}




getUserBasedModel()



