import java.io.{BufferedWriter, File, FileWriter}
import scala.language.postfixOps
import scala.io.{BufferedSource, Source}
import scala.math.sqrt
import scala.collection.parallel.CollectionConverters._
import scala.collection.parallel.ParSeq

def in: BufferedSource = Source.fromFile(getClass.getClassLoader.getResource("train_triplets_2048.txt").getPath)

/*
trait ArrayTree[A] { val size: Int }
case class ArrayLeaf[A](a: Array[A]) extends ArrayTree[A] {
  override val size = a.size
}
case class ArrayNode[A](l: ArrayTree[A], r: ArrayTree[A]) extends ArrayTree[A] {
  override val size = l.size + r.size
}
def sizeLeaf = 915

def createTree(list: Array[String], start: Int, leafSize: Int): ArrayTree[String] = {
  if (list.length <= leafSize) new ArrayLeaf[String]( list.drop(start).take(leafSize) )
  else new ArrayNode[String](createTree(list, 0, leafSize/2),createTree(list, leafSize/2, leafSize))
}
*/

// load all songs
val songs = in.getLines().toArray map (line => line split "\t" slice(1,2) mkString) distinct
// load all users
val users = in.getLines().toArray map (line => line split "\t" slice(0,1) mkString) distinct

/*
val songsTree = createTree(songs, 0, songs.length/2)
val usersTree = createTree(users, 0, users.length/2)
*/

// TEST-ONLY: this is a subset of all users
val usedUsers = users.par slice(0,100)
// TEST-ONLY: this is a subset of all songs
val usedSongs = songs.par slice(0,100)

// given a user, it returns a list of all the songs (s)he listened to
def songsFilteredByUser(user:String) : List[String] = (for {
  line <- in.getLines().toList.filter(line => line.contains(user))
} yield line split "\t" match {
  case Array(_, song, _) => song
}) distinct

// create a map user1->[song1, song2, ...], user2->[song3,...]
val usersToSongsMap = users map (user => (user, songsFilteredByUser(user))) toMap

// if the user listened to both songs return 1, else 0
def numerator(song1: String, song2: String, user: String): Int = {
  // ParSeq do not have contains method, so we use exists parallel method
  if (usersToSongsMap(user).exists(_ == song1) && usersToSongsMap(user).exists(_ == song2)) 1 else 0
}

// it calculates the cosine similarity between two songs
def cosineSimilarity(song1: String, song2: String): Double = {
  /*
    // for each user, check if (s)he has listened to both the songs
    val sameUsers = (usedUsers map (user => numerator(song1, song2, user))).sum
    // for each song, if user1 has listened to it sum 1 and at the end apply the square root
    val rootSong1 = sqrt((usedUsers map (user => if (usersToSongsMap(user).exists(_ == song1)) 1 else 0)).sum)
    // for each song, if user2 has listened to it sum 1 and at the end apply the square root
    val rootSong2 = sqrt((usedUsers map (user => if (usersToSongsMap(user).exists(_ == song2)) 1 else 0)).sum)
    // calculate cosine similarity
    sameUsers / (rootSong1 * rootSong2)
  */
  val a = usedUsers.map(user => (numerator(song1, song2, user),
                                  if (usersToSongsMap(user).exists(_ == song1)) 1 else 0,
                                  if (usersToSongsMap(user).exists(_ == song2)) 1 else 0))
  val b = a.aggregate((0,0,0))(
    (acc, tupl) => (acc._1 + tupl._1, acc._2 + tupl._2, acc._3 + tupl._3),
    (acc, tupl) => (acc._1 + tupl._1, acc._2 + tupl._2, acc._3 + tupl._3),
  )
  b._1 / (sqrt(b._2) * sqrt(b._3))
}

def formula(weightFunction: (String, String) => Double): ParSeq[(String, String, Double)] = {

  def specificFormula(user: String, song: String): Double = {
    for { s2 <- usedSongs filter (s => s != song)}
      yield {
        val listened = if(usersToSongsMap(user).exists(_ == s2)) 1 else 0
        listened*weightFunction(song, s2)
      }
  } sum

  def rank(): ParSeq[(String, String, Double)] = {
    for {
      u <- usedUsers
      s <- usedSongs filter(song => !usersToSongsMap(u).exists(_ == song))
    } yield {
      def rank: Double = specificFormula(u,s)
      (u, s, rank)
    }
  }

  rank()
}

def time[R](block: => R): R = {
  // get start time
  val t0 = System.nanoTime()
  // execute code
  val result = block
  // get end time
  val t1 = System.nanoTime()
  // print elapsed time
  println("Elapsed time: " + (t1 - t0)/1000000 + "ms")
  // return the result
  result
}

// ParSeq do not have sort methods
//val itemBasedModel = time(formula(cosineSimilarity)) sorted(Ordering.by[(String, String, Double), Double](_._3) reverse) groupBy(_._1)
val itemBasedModel = time(formula(cosineSimilarity)) groupBy(_._1)
val f = new File(getClass.getClassLoader.getResource("models/itemBasedModelP.txt").getPath)
val bw = new BufferedWriter(new FileWriter(f))
itemBasedModel map(el => {
  el._2 map (row => {
    bw.write(row._1 + "\t" + row._2 + "\t" + row._3 + "\n")
  })
})
bw.close()