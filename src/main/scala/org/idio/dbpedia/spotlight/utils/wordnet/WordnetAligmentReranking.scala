package org.idio.dbpedia.spotlight.utils.wordnet

import scala.collection.mutable
import org.idio.dbpedia.spotlight.CustomSpotlightModel
import org.dbpedia.spotlight.model.DBpediaResource
import org.idio.wordnet.{WordnetMappingParser, WordnetInterface}
import java.io.{PrintWriter, File}

/**
 * Copyright 2014 Idio
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/**
 * @author David Przybilla david.przybilla@idioplatform.com
 **/


object WordnetAligmentReranking{


  def main(args:Array[String]){

    println("reading model...")
    val pathToModel = args(0)
    val pathToWordnetAlignment = args(1)
    val outputFilePath = args(2)
    val wordnetAlignment = WordnetMappingParser.readMapping(pathToWordnetAlignment)
    val reranking = new WordnetAligmentReranking(pathToModel, wordnetAlignment, outputFilePath)
    reranking.updateModel()

  }

}

//Wordnet aligment wikipediaId->WordnetId
// wordnetID -> offSet-POS i.e: 123-n
class WordnetAligmentReranking(pathToModel: String, wordnetAligment: mutable.HashMap[String, String], outputFilePath: String){


   val spotlightModel = new CustomSpotlightModel(pathToModel)

   private def findMatchingTopics(): List[DBpediaResource] = {

     val foundDbpediaResources = wordnetAligment.keySet.par.flatMap{
       wikipediaID: String =>
          try{
             Some(spotlightModel.customDbpediaResourceStore.resStore.getResourceByName(wikipediaID))
          }catch{
            case e:Exception =>
             println("WARNING WIKIPEDIA ID NOT FOUND " + wikipediaID )
             None
          }
     }

     foundDbpediaResources.toList
   }

  /*
  * Gets the lemmas for each topic
  * Returns a list of (dbpediaResource, lemmasOfDbpeidaResource)
  * */
  private def getWordnetLemmas(listOfTopics: List[DBpediaResource]): List[(DBpediaResource, List[String])] ={

    listOfTopics.flatMap{
      topic : DBpediaResource =>
        var synsetId = ""
        try{
          synsetId = wordnetAligment.get(topic.uri).get

           val lemmas = WordnetInterface.getWordnetLemmas(synsetId)
           println("lemmas...\t"+ topic + lemmas)
           Some((topic, lemmas))
        }catch{

          case e:Exception => {
             println("NO LEMMA FOUND...\t"+ topic +" synsetID:"+synsetId)
             e.printStackTrace()
             None
          }

        }
    }

  }


 private def expandLemma(lemma:String): List[String] = {

    val expandedLemmas = scala.collection.immutable.HashSet( lemma,
                                                             lemma.toLowerCase,
                                                             lemma.capitalize,
                                                             lemma.split(" ").map(_.capitalize).mkString(" "))

    expandedLemmas.toList
  }


def checkSurfaceFormInModel(dbpediaTopic: DBpediaResource, sf:String): Boolean ={

   // check if the surface form exists
  try{
      val surfaceForm = this.spotlightModel.customSurfaceFormStore.sfStore.getSurfaceForm(sf)

      val annotationProbability =surfaceForm.annotationProbability
      if (annotationProbability < 0.27) {
        return false
      }

      // check association between surfaceForm and Topic
      val candidates = this.spotlightModel.customCandidateMapStore.candidateMap.getCandidates(surfaceForm)
       candidates.find(_.resource.id == dbpediaTopic.id ) match {
         case None => false
         case Some(s) => true
       }

  }catch{

    case e:Exception =>{
        false
    }
  }

}



 private def updateSurfaceformsAndAssociations( listOfTopicLemmas: List[(DBpediaResource, List[String])]){

   val outputFile = new PrintWriter(new File(outputFilePath))
   listOfTopicLemmas.foreach{

     case(topic:DBpediaResource, lemmas:List[String]) =>{
       lemmas.foreach{
         lemma: String =>
           val filteredLemmas = expandLemma(lemma).filter{ lemma => !checkSurfaceFormInModel(topic, lemma)}
           if (filteredLemmas.size >0 )
              outputFile.println(topic.uri + "\t" + filteredLemmas.mkString("|"))
       }
     }
   }

 }



  def updateModel(){
    //Get the topics described in the mapping which exists in the model
    val matchingTopics = findMatchingTopics()

    //Get the lemmas of the matching topics
    val listOfTopicLemmas = getWordnetLemmas(matchingTopics)

    println("Updating sf-associations")
    updateSurfaceformsAndAssociations(listOfTopicLemmas)

    println("exporting model")
    spotlightModel.exportModels(pathToModel)
  }



}