import music_recommandation.MusicRecommender
import my_utils.MyUtils.time

import scala.collection.immutable._
import scala.collection.parallel.CollectionConverters._
import scala.io._
import scala.language._

object main {
  def main(args: Array[String]): Unit = {
    // 0: sequential, 1: parallel, 2: distributed
    val execution = 1
    // verbosity of the output
    val verbose = true
    // name of the file containing the considered dataset
    val fileName: String = "train_triplets_50k.txt"
    // number of (maximum) users and songs to consider
    val nUsedUsers = 500
    val nUsedSongs = 500

    // import dataset
    def in: BufferedSource = Source.fromFile(getClass.getClassLoader.getResource(fileName).getPath)
    // load all users
    val users = in.getLines().toList map (line => line split "\t" slice(0, 1) mkString) distinct
    // load all songs
    val songs = in.getLines().toList map (line => line split "\t" slice(1, 2) mkString) distinct

    // print number of total users and songs in the file
    if (verbose) println(s"File \'$fileName\' contains ${users.length} users and ${songs.length} songs")

    // TEST-ONLY: using a subset of users and songs
    val (usedUsers: IterableOnce[String], usedSongs: IterableOnce[String]) = {
      if (execution == 0) (users slice(0, nUsedUsers), songs slice(0, nUsedSongs))
      else if (execution == 1) (users.par slice(0, nUsedUsers), songs.par slice(0, nUsedSongs))
      else if (execution == 2) {if (verbose) println("\n! Todo !\n"); System.exit(1)}
      else {if (verbose) println("\n! Error !\n"); System.exit(-1)}
    }
    // print number of users and songs that are actually being used
    if (verbose) println(s"Using ${usedUsers.iterator.length} users and ${usedSongs.iterator.length} songs")

    // given a user, it returns a list of all the songs (s)he listened to
    def songsFilteredByUser(user: String): List[String] = (for {
      line <- in.getLines().toList.filter(line => line.contains(user))
    } yield line split "\t" match {
      case Array(_, song, _) => song
    }) distinct

    // create a map user1->[{songs listened by user1}], ..., userN->[{songs listened by userN}]
    // making this structure an IterableOnce with parallelization in mind does not improve performances
    val usersToSongsMap = usedUsers.iterator.toSeq.map(user => user -> songsFilteredByUser(user)) toMap

    // instantiate musicRecommender
    val musicRecommender: MusicRecommender = new MusicRecommender(usedUsers, usedSongs, usersToSongsMap, execution)

    // calculating user-based model
    musicRecommender.getUserBasedModel()
    musicRecommender.getItemBasedModel()
    musicRecommender.getLinearCombinationModel(0.5)
    musicRecommender.getAggregationModel(0.5)
    musicRecommender.getStochasticCombinationModel(0.5)
  }
}
