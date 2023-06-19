# Music Recommendation
Repository for the project of the Scalable and Cloud Computing course @unibo, a.y. 2022-23.

Inspired by the Million Song Dataset challenge on Kaggle, a comparison of various solutions to the problem
exploiting the MapReduce paradigm and providing an environment for sequential, parallel and distributed
execution. Hence, we aim to provide not only a comparison between the different algorithms, but also an
analysis of the behaviours of these algorithms across the different executions.

The different algorithms produced can be found in the `src/main/scala/` directory as scala worksheet
files. To execute the algorithms, one should download the <b>Echo Nest Taste Profile Subset</b> dataset from
http://millionsongdataset.com/tasteprofile/ and place the `train_triplets.txt` file under the `src/main/resources/`
directory. Once executed, the results of the training will be available through a text file with the same name of the
algorithm which can be found under `target/scala-2.13/classes/models/`. Make sure to also have an empty file in
`src/main/resources/models/` with the same name of the model (in case not, create it, as it will be used to dynamically
create the path for the output model, which will still be found under the `target/scala-2.13/classes/models/` path).

We plan to implement the following algorithms:
- UserBasedModel
- ItemBasedModel
- LinearCombinationModel
- AggregationModel
- StochasticAggregationModel

## How to execute code on Google Cloud Platform
1. Be sure that:
    * Every file path in code is in the local path (e.g., "train_100_50.txt" instead of "~/MusicReccomender/.../train_100_50.txt")
    * All files are loaded with `Source.fromResource` instead of `Source.fromFile`
    * You have already created a Google Cloud Project ([here's a guide](https://cloud.google.com/dataproc/docs/guides/setup-project))
    * You have already installed Google Cloud CLI ([here's a guide](https://cloud.google.com/sdk/docs/install))
2. Create an assembly *.jar* package using the following command (in the project directory):
   ```sh
   sbt clean assembly
   ```
   * (Optional, but strongly recommended) You can check if the code works: go to `MusicReccomender/target/scala-2.12/` and run
     ```shell
       java -jar package.jar
       ```
3. Create a new bucket inside the project in Google Cloud Platform (in this example it is called `mr`)
4. Copy the package created inside `MusicReccomender/target/scala-2.12/` to the bucket with the following command:
   ```shell
   gsutil cp package.jar gs://mr
   ```
5. Create a Compute Engine Cluster on Google Cloud Platform
    * Be careful to choose the correct linux image, which is *2.1 (Debian 11, Hadoop 3.3, Spark 3.3) first release: 22/01/2021*
    * You can plan the elimination of the cluster so that you won't waste credits 
6. Create and send a new job by:
   1. Selecting the created cluster
   2. Choosing *Spark* job  as "Type of job"
   3. Typing `gs://mr/package.jar` in "Main class or jar" 

## Some notes about some choices made during the project

### getModel alternatives:

- using only map:

```
songs.map(s => if (!testUsersToSongsMap(user).contains(s)) user -> (s, rank(user, s)) else user -> (s, 0.0))
```

- using external map and internal for:

```
songs map (s => {
  for {
    u <- testUsers filter (u => !testUsersToSongsMap(u).contains(s))
  } yield u -> (s, rank(u, s))
})
```

- using only for:

```
for {
  s <- songs
  u <- testUsers
  if !testUsersToSongsMap(u).contains(s)
} yield u -> (s, rank(u, s))
```

The latter has been (sperimentally) proven to be the most efficient for both sequential and parallel computations, while in the distributed version the second works better, as we can distribute only on one RDD (more considerations on this later).

- Parallel version of the third alternative:

```
for {
  s <- songs.iterator.toSeq.par
  u <- testUsers.iterator.toSeq.par
  if !testUsersToSongsMap(u).contains(s)
} yield u -> (s, rank(u, s))
```

In the for construct over both structures, the if statement has been proven to work better than the filter, differently from the other cases (i.e., for over one single structure and map).

- Distributed version of the second alternative:

```
object _BasedModel {

  ...

  def getRanks1(user: String):ParSeq[(String, (String, Double))] = {
    // foreach song, calculate the score for the user
    for {
      song <- songs.iterator.toSeq.par filter (song => !testUsersToSongsMap(user).contains(song))
    } yield {
      user -> (song, rank(user, song))
    }
  }

  def getRanks2(song: String): ParSeq[(String, (String, Double))] = {
    // foreach user, calculate the score for the user
    for {
      user <- testUsers.iterator.toSeq.par filter (user => !testUsersToSongsMap(user).contains(song))
    } yield {
      user -> (song, rank(user, song))
    }
  }
}

...

ctx.parallelize(testUsers).map(user => _BasedModel.getRanks1(user).seq).collect.flatten
ctx.parallelize(songs).map(song => _BasedModel.getRanks2(song).seq).collect.flatten

```

About distribution, we also have to take in account the number of nodes and cores per node that we can use. For
instance, in our case dividing the computation over the songs has proven to be better than dividing over the number of
users in all the experiments, therefore if we had more nodes than cores per node we would distribute over the songs
mapping them over the getRanks2 function; otherwise, if we had more cores per node rather than nodes, we would
distribute over the users mapping them over the getRanks1 function and making use of the greater parallelization
happening inside every single node. The same valuations stand in case for some reason the number of users grows
exponentially while the number of songs stays the same.

## Experiments

The following results are obtained on an Intel® Core™ i5-8250U Processor, 6M Cache, up to 3.40 GHz, 4 cores, 8 threads.

- Sequential:

| TrainUsers | TestUsers | Songs   | ubm        | ibm      | lcm   | am    | scm   | mAP (mean) |
|------------|-----------|---------|------------|----------|-------|-------|-------|------------|
| 100        | 10        | 4798    | 70306ms    | 42946ms  | 33ms  | 48ms  | 68ms  | 243        |
| 100        | 50        |         |            |          |       |       |       |            |
| 100        | 100       |         |            |          |       |       |       |            |


- Parallel:

| TrainUsers | TestUsers | Songs   | ubm     | ibm      | lcm   | am   | scm   | mAP (mean) |
|------------|-----------|---------|---------|----------|-------|------|-------|------------|
| 100        | 10        | 4798    | 24243ms | 16757ms  | 20ms  | 48ms | 15ms  | 200        |
| 100        | 50        |         |         |          |       |      |       |            |
| 100        | 100       |         |         |          |       |      |       |            |

- Distributed (note: take this with a grain of salt, as we are running Spark locally, therefore virtualizing the
distribution)

| TrainUsers | TestUsers | Songs   | ubm     | ibm     | lcm    | acm    | scm    | mAP (mean) |
|------------|-----------|---------|---------|---------|--------|--------|--------|------------|
| 100        | 10        | 4798    | 20484ms | 8306ms  | 346ms  | 317ms  | 327ms  | 424        |
| 100        | 50        |         |         |         |        |        |        |            |
| 100        | 100       |         |         |         |        |        |        |            |

- Results with 100 train users and 10 test users:

  - Songs: 4798
  
  - Elapsed time for (Sequential) user-based model:	                  70306ms (70306563208ns)
  - Elapsed time for (Parallel) user-based model:	                  24243ms (24243114162ns)
  - Elapsed time for (Distributed) user-based:	                      20484ms (20484129170ns)
  - Elapsed time for (Sequential) item-based model:	                  42946ms (42946929802ns)
  - Elapsed time for (Parallel) item-based model:	                  16757ms (16757097299ns)
  - Elapsed time for (Distributed) item-based:	                      8306ms (8306233090ns)
  - Elapsed time for (Sequential) linear-combination model:	          33ms (33932444ns)
  - Elapsed time for (Parallel) linear-combination model:	          20ms (20231505ns)
  - Elapsed time for (Distributed) linear combination:	              346ms (346816304ns)
  - Elapsed time for (Sequential) aggregation model:	              48ms (48108313ns)
  - Elapsed time for (Parallel) aggregation model:	                  48ms (33782193ns)
  - Elapsed time for (Distributed) aggregation model:	              317ms (317630317ns)
  - Elapsed time for (Sequential) stochastic-combination model:	      68ms (68734158ns)
  - Elapsed time for (Parallel) stochastic-combination model:	      15ms (15197487ns)
  - Elapsed time for (Distributed) stochastic combination model:	  327ms (327777721ns)

  - Elapsed time for (Sequential) user-based model mAP:	              335ms (335303294ns)
  - Elapsed time for (Parallel) user-based model mAP:	              223ms (223572416ns)
  - Elapsed time for (Distributed) user-based model mAP:	          442ms (442476757ns)
  - Elapsed time for (Sequential) item-based model mAP:	              236ms (236702295ns)
  - Elapsed time for (Parallel) item-based model mAP:	              222ms (222743490ns)
  - Elapsed time for (Distributed) item-based model mAP:	          543ms (543726507ns)
  - Elapsed time for (Sequential) linear-combination model mAP:	      218ms (218843655ns)
  - Elapsed time for (Parallel) linear-combination model mAP:	      174ms (174673005ns)
  - Elapsed time for (Distributed) linear-combination model mAP:	  470ms (470986546ns)
  - Elapsed time for (Sequential) aggregation model mAP:	          213ms (213448581ns)
  - Elapsed time for (Parallel) aggregation model mAP:	              212ms (212537718ns)
  - Elapsed time for (Distributed) aggregation model mAP:	          301ms (301007479ns)
  - Elapsed time for (Sequential) stochastic-combination model mAP:	  213ms (213216395ns)
  - Elapsed time for (Parallel) stochastic-combination model mAP:	  167ms (167907171ns)
  - Elapsed time for (Distributed) stochastic-combination model mAP:  363ms (363570856ns)

  - (Sequential) user-based model mAP:              0.06180479825517996
  - (Parallel) user-based model mAP:                0.06180479825517996
  - (Distributed) user-based model mAP:             0.06180479825517996
  - (Sequential) item-based model mAP:              0.09904580152671755
  - (Parallel) item-based model mAP:                0.09904580152671755
  - (Distributed) item-based model mAP:             0.09904580152671755
  - (Sequential) linear-combination model mAP:      0.1025445292620865
  - (Parallel) linear-combination model mAP:        0.1025445292620865
  - (Distributed) linear-combination model mAP:     0.1025445292620865
  - (Sequential) aggregation model model mAP:       0.06371319520174483
  - (Parallel) aggregation model model mAP:         0.06371319520174483
  - (Distributed) aggregation model mAP:            0.06371319520174483
  - (Sequential) stochastic-combination model mAP:  0.08207015630679752
  - (Parallel) stochastic-combination model mAP:    0.06825699745547072
  - (Distributed) stochastic-combination model mAP: 0.07147400945110868
