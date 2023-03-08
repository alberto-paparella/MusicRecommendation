package music_recommandation

import scala.io.{BufferedSource, Source}
import scala.language.postfixOps
import scala.collection.parallel.CollectionConverters._

object main {
  def main(args: Array[String]): Unit = {
    // 0: sequential, 1: parallel, 2: distributed
    val execution = 1
    // name of the file containing the considered dataset
    val fileName: String = "train_triplets_50k.txt"
    // import dataset
    def in: BufferedSource = Source.fromFile(getClass.getClassLoader.getResource(fileName).getPath)
    // load all users
    val users = in.getLines().toList map (line => line split "\t" slice(0, 1) mkString) distinct
    // load all songs
    val songs = in.getLines().toList map (line => line split "\t" slice(1,2) mkString) distinct

    // TEST-ONLY: print number of total users and songs in the file (as we may work on smaller files)
    println(s"Songs number:\t${songs.length}\nUsers number:\t${users.length}")
    // TEST-ONLY: number of (maximum) users and songs to consider
    val nUsedUsers = 300
    val nUsedSongs = 300
    // TEST-ONLY: using a subset of users and songs
    val (usedUsers : IterableOnce[String], usedSongs : IterableOnce[String]) = {
      if (execution == 0) (users slice(0,nUsedUsers), songs slice(0,nUsedSongs))
      else if (execution == 1) (users.par slice(0,nUsedUsers), songs.par slice(0,nUsedSongs))
      else println("\n! Error !\n")
    }

    // given a user, it returns a list of all the songs (s)he listened to
    def songsFilteredByUser(user:String): List[String] = (for {
      line <- in.getLines().toList.filter(line => line.contains(user))
    } yield line split "\t" match {
      case Array(_, song, _) => song
    }) distinct

    // create a map user1->[song1, song2, ..., songN], user2->[song1, song2, ..., songN]
    val usersToSongsMap = users map (user => user -> songsFilteredByUser(user)) toMap

    val musicRecommender = {
      if (execution == 0) new MusicRecommender(usedUsers, usedSongs, usersToSongsMap, parallel = false)
      else if (execution == 1) new MusicRecommender(usedUsers, usedSongs, usersToSongsMap, parallel = true)
      else new MusicRecommender(usedUsers, usedSongs, usersToSongsMap)
    }

    if (execution == 0) {
      println("Starting sequential evaluation")
      //musicRecommender.getItemBasedModelRank("models/itemBasedModel.txt")
      musicRecommender.getUserBasedModelRank("models/userBasedModel.txt")
      //musicRecommender.getLinearCombinationModelRank(0.5, parallel = false, "models/linearCombination.txt")
    }
    else if (execution == 1) {
      println("\nStarting parallel evaluation")
      //musicRecommender.getItemBasedModelRank("models/itemBasedModelP.txt")
      musicRecommender.getUserBasedModelRank("models/userBasedModelP.txt")
      //musicRecommender.getLinearCombinationModelRank(0.5, parallel=true,"models/linearCombinationP.txt")
    }
    else println("\n! Error !\n")
  }
}
