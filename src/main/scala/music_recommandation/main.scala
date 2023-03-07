package music_recommandation;
import music_recommandation.MusicRecommender

import scala.io.{BufferedSource, Source}
import scala.language.postfixOps;
import scala.collection.parallel.CollectionConverters._
import scala.collection.parallel.ParSeq

object main {
  def main(args: Array[String]) = {

    val fileName: String = "train_triplets_10k.txt"
    def in: BufferedSource = Source.fromFile(getClass.getClassLoader.getResource(fileName).getPath)

    val songs = in.getLines().toList map (line => line split "\t" slice(1,2) mkString) distinct
    val users = in.getLines().toList map (line => line split "\t" slice(0,1) mkString) distinct

    println(s"Songs number:\t${songs.length}\nUsers number:\t${users.length}")

    // TEST-ONLY: subset of all users and songs
    val (usedSongsSeq, usedUsersSeq) = (songs slice(0,500), users slice(0,500))
    val (usedSongsPar, usedUsersPar) = (songs.par slice(0,500), users.par slice(0,500))

    // given a user, it returns a list of all the songs (s)he listened to
    def songsFilteredByUser(user:String): List[String] = (for {
      line <- in.getLines().toList.filter(line => line.contains(user))
    } yield line split "\t" match {
      case Array(_, song, _) => song
    }) distinct

    // create a map user1->[song1, song2, ...], user2->[song3,...]
    val usersToSongsMap = users map (user => (user -> songsFilteredByUser(user))) toMap

    val sequentialRecommender = new MusicRecommender(usedUsersSeq, usedSongsSeq, usersToSongsMap)
    val parallelRecommender = new MusicRecommender(usedUsersPar, usedSongsPar, usersToSongsMap)

    println("Starting sequential evaluation")
    sequentialRecommender.getItemBasedModelRank("models/itemBasedModel.txt")
    sequentialRecommender.getUserBasedModelRank("models/userBasedModel.txt")
    sequentialRecommender.getLinearCombinationModelRank(0.5, false, "models/linearCombination.txt")
    println("\nStarting parallel evaluation")
    parallelRecommender.getItemBasedModelRank("models/itemBasedModelP.txt")
    parallelRecommender.getUserBasedModelRank("models/userBasedModelP.txt")
    parallelRecommender.getLinearCombinationModelRank(0.5, true,"models/linearCombinationP.txt")
  }
}
