package com.shabinder.common

import co.touchlab.kermit.Logger
import com.beust.klaxon.JsonArray
import com.beust.klaxon.JsonObject
import com.beust.klaxon.Parser
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import me.xdrop.fuzzywuzzy.FuzzySearch
import kotlin.math.absoluteValue

private const val apiKey = "AIzaSyC9XL3ZjWddXya6X74dJoCTL-WEYFDNX30"

actual class YoutubeMusic actual constructor(
    private val logger: Logger,
    private val httpClient:HttpClient,
) {
    private val tag = "YTMUSIC"
    actual fun getYTTracks(response: String):List<YoutubeTrack>{
        val youtubeTracks = mutableListOf<YoutubeTrack>()

        val stringBuilder: StringBuilder = StringBuilder(response)
        val responseObj: JsonObject = Parser.default().parse(stringBuilder) as JsonObject
        val contentBlocks = responseObj.obj("contents")?.obj("sectionListRenderer")?.array<JsonObject>("contents")
        val resultBlocks = mutableListOf<JsonArray<JsonObject>>()
        if (contentBlocks != null) {
            for (cBlock in contentBlocks){
                /**
                 *Ignore user-suggestion
                 *The 'itemSectionRenderer' field is for user notices (stuff like - 'showing
                 *results for xyz, search for abc instead') we have no use for them, the for
                 *loop below if throw a keyError if we don't ignore them
                 */
                if(cBlock.containsKey("itemSectionRenderer")){
                    continue
                }

                for(contents in cBlock.obj("musicShelfRenderer")?.array<JsonObject>("contents") ?: listOf()){
                    /**
                     *  apparently content Blocks without an 'overlay' field don't have linkBlocks
                     *  I have no clue what they are and why there even exist
                     *
                    if(!contents.containsKey("overlay")){
                    println(contents)
                    continue
                    TODO check and correct
                    }*/

                    val result = contents.obj("musicResponsiveListItemRenderer")
                        ?.array<JsonObject>("flexColumns")

                    //Add the linkBlock
                    val linkBlock = contents.obj("musicResponsiveListItemRenderer")
                        ?.obj("overlay")
                        ?.obj("musicItemThumbnailOverlayRenderer")
                        ?.obj("content")
                        ?.obj("musicPlayButtonRenderer")
                        ?.obj("playNavigationEndpoint")

                    // detailsBlock is always a list, so we just append the linkBlock to it
                    // instead of carrying along all the other junk from "musicResponsiveListItemRenderer"
                    linkBlock?.let { result?.add(it) }
                    result?.let { resultBlocks.add(it) }
                }
            }

            /* We only need results that are Songs or Videos, so we filter out the rest, since
            ! Songs and Videos are supplied with different details, extracting all details from
            ! both is just carrying on redundant data, so we also have to selectively extract
            ! relevant details. What you need to know to understand how we do that here:
            !
            ! Songs details are ALWAYS in the following order:
            !       0 - Name
            !       1 - Type (Song)
            !       2 - com.shabinder.spotiflyer.models.gaana.Artist
            !       3 - Album
            !       4 - Duration (mm:ss)
            !
            ! Video details are ALWAYS in the following order:
            !       0 - Name
            !       1 - Type (Video)
            !       2 - Channel
            !       3 - Viewers
            !       4 - Duration (hh:mm:ss)
            !
            ! We blindly gather all the details we get our hands on, then
            ! cherrypick the details we need based on  their index numbers,
            ! we do so only if their Type is 'Song' or 'Video
            */

            for(result in resultBlocks){

                // Blindly gather available details
                val availableDetails = mutableListOf<String>()

                /*
                Filter Out dummies here itself
                ! 'musicResponsiveListItemFlexColumnRenderer' should have more that one
                ! sub-block, if not its a dummy, why does the YTM response contain dummies?
                ! I have no clue. We skip these.

                ! Remember that we appended the linkBlock to result, treating that like the
                ! other constituents of a result block will lead to errors, hence the 'in
                ! result[:-1] ,i.e., skip last element in array '
                */
                for(detail in result.subList(0,result.size-1)){
                    if(detail.obj("musicResponsiveListItemFlexColumnRenderer")?.size!! < 2) continue

                    // if not a dummy, collect All Variables
                    val details = detail.obj("musicResponsiveListItemFlexColumnRenderer")
                        ?.obj("text")
                        ?.array<JsonObject>("runs") ?: listOf()
                    for (d in details){
                        d["text"]?.let {
                            if(it.toString() != " • "){
                                availableDetails.add(
                                    it.toString()
                                )
                            }
                        }
                    }
                }
//            log("YT Music details",availableDetails.toString())
                /*
                ! Filter Out non-Song/Video results and incomplete results here itself
                ! From what we know about detail order, note that [1] - indicate result type
                */
                if ( availableDetails.size == 5 && availableDetails[1] in listOf("Song","Video") ){

                    // skip if result is in hours instead of minutes (no song is that long)
                    if(availableDetails[4].split(':').size != 2) continue

                    /*
                    ! grab Video ID
                    ! this is nested as [playlistEndpoint/watchEndpoint][videoId/playlistId/...]
                    ! so hardcoding the dict keys for data look up is an ardours process, since
                    ! the sub-block pattern is fixed even though the key isn't, we just
                    ! reference the dict keys by index
                    */

                    val videoId:String? = result.last().obj("watchEndpoint")?.get("videoId") as String?
                    val ytTrack = YoutubeTrack(
                        name = availableDetails[0],
                        type = availableDetails[1],
                        artist = availableDetails[2],
                        duration = availableDetails[4],
                        videoId = videoId
                    )
                    youtubeTracks.add(ytTrack)

                }
            }
        }
        logger.i(youtubeTracks.joinToString(" abc \n"),tag)
        return youtubeTracks
    }

    actual fun sortByBestMatch(ytTracks:List<YoutubeTrack>,
                               trackName:String,
                               trackArtists:List<String>,
                               trackDurationSec:Int,
    ):Map<String,Int>{
        /*
        * "linksWithMatchValue" is map with Youtube VideoID and its rating/match with 100 as Max Value
        **/
        val linksWithMatchValue = mutableMapOf<String,Int>()

        for (result in ytTracks){

            // LoweCasing Name to match Properly
            // most song results on youtube go by $artist - $songName or artist1/artist2
            var hasCommonWord = false

            val resultName = result.name?.toLowerCase()?.replace("-"," ")?.replace("/"," ") ?: ""
            val trackNameWords = trackName.toLowerCase().split(" ")

            for (nameWord in trackNameWords){
                if (nameWord.isNotBlank() && FuzzySearch.partialRatio(nameWord,resultName) > 85) hasCommonWord = true
            }

            // Skip this Result if No Word is Common in Name
            if (!hasCommonWord) {
                //log("YT Api Removing", result.toString())
                continue
            }


            // Find artist match
            // Will Be Using Fuzzy Search Because YT Spelling might be mucked up
            // match  = (no of artist names in result) / (no. of artist names on spotify) * 100
            var artistMatchNumber = 0

            if(result.type == "Song"){
                for (artist in trackArtists){
                    if(FuzzySearch.ratio(artist.toLowerCase(),result.artist?.toLowerCase()) > 85)
                        artistMatchNumber++
                }
            }else{//i.e. is a Video
                for (artist in trackArtists) {
                    if(FuzzySearch.partialRatio(artist.toLowerCase(),result.name?.toLowerCase()) > 85)
                        artistMatchNumber++
                }
            }

            if(artistMatchNumber == 0) {
                //log("YT Api Removing", result.toString())
                continue
            }

            val artistMatch = (artistMatchNumber / trackArtists.size ) * 100

            // Duration Match
            /*! time match = 100 - (delta(duration)**2 / original duration * 100)
            ! difference in song duration (delta) is usually of the magnitude of a few
            ! seconds, we need to amplify the delta if it is to have any meaningful impact
            ! wen we calculate the avg match value*/
            val difference = result.duration?.split(":")?.get(0)?.toInt()?.times(60)
                ?.plus(result.duration?.split(":")?.get(1)?.toInt()?:0)
                ?.minus(trackDurationSec)?.absoluteValue ?: 0
            val nonMatchValue :Float= ((difference*difference).toFloat()/trackDurationSec.toFloat())
            val durationMatch = 100 - (nonMatchValue*100)

            val avgMatch = (artistMatch + durationMatch)/2
            linksWithMatchValue[result.videoId.toString()] = avgMatch.toInt()
        }
        //log("YT Api Result", "$trackName - $linksWithMatchValue")
        return linksWithMatchValue.toList().sortedByDescending { it.second }.toMap()
    }

    actual suspend fun getYoutubeMusicResponse(query: String):String{
        return httpClient.post("https://music.youtube.com/youtubei/v1/search?alt=json&key=$apiKey"){
            contentType(ContentType.Application.Json)
            headers{
                //append("Content-Type"," application/json")
                append("Referer"," https://music.youtube.com/search")
            }
            body = buildJsonObject {
                putJsonObject("context"){
                    putJsonObject("client"){
                        put("clientName" ,"WEB_REMIX")
                        put("clientVersion" ,"0.1")
                    }
                }
                put("query",query)
            }
        }
    }
}