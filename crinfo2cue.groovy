#! /usr/bin/groovy
/*
Copyright (c) Paul C. Casto
Released under MIT license
This file is part of CyanRip Unified File Tools (cruft)
*/

/* TODO
 - split this into separate files as classes, maybe something like:
    -- utilities
    -- cue builder
    -- extract runner & merger
    -- metadata adder
 - for cue (the core of this current file)
   -- read from command line, or config
   -- write to filename.cue
*/

// for testing against files, rather than live CD
String infoFile = (args.length > 0) ? args[0] : null

String cyanrip='/home/paul/git/cyanrip/build/src/cyanrip' // this provides the -J option, but maybe 0.9.3 is OK
Integer offset = 6   // this could be set on a per drive basis when multiple drives are available
String cueComment = "Created by CRUFT"
String outputType = 'flac'
// this approach avoids 'eval' but even so, could be subject to abuse
// if any of these values (taken from metadata) could be manipulated upstream, then bad things could happen.
// e.g. conceptually if album_artist were 'bad guys; rm *' and a careless mkdir outputDir were issued, then boom.
// This code will pass arrays, rather than strings to avoid shell expansion, but need to review any possible holes.
// the $ in front is not strictly needed -- but deconflicts in cases where text term collides with metadata 
// e.g. to express something like (disc 1 of 2)
List outputDir = ['/mnt/Media/Music/IMAGES/', '$album_artist']
String cueFile = "temp.cue"

Map filenameFormats = [
    standard    : ['$album_artist',   ' - (', '$year', ') ', '$album',                                       '.', outputType ],
    multidisc   : ['$album_artist',   ' - (', '$year', ') ', '$album',' [','$disc', '/', '$totaldiscs', ']', '.', outputType ],
    vastandard  : ['Various Artists', ' - (', '$year', ') ', '$album',                                       '.', outputType ],
    vamultidisc : ['Various Artists', ' - (', '$year', ') ', '$album',' [','$disc', '/', '$totaldiscs', ']', '.', outputType ]
]

import java.util.regex.Matcher
import java.time.Year
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

// get the year from MBZ date string
def extractYear(String dateStr) {
    // what date patterns will be sent frm MBZ ?
    def patterns  = ["yyyy-MM-dd", "yyyy", "dd/MM/yyyy", "MMM dd, yyyy", "MM-dd-yyyy"]
    for (String pattern : patterns) {
        try {
            def formatter = DateTimeFormatter.ofPattern(pattern)
            return Year.parse(dateStr, formatter)
        } catch (DateTimeParseException e) {
            // Try the next pattern
        }
    }
    throw new IllegalArgumentException("Unable to parse date: $dateStr")
}

/**
 * Converts a CD Logical Sector Number (LSN/LBA) into a Cue Sheet timestamp string (MM:SS:FF).
 * @param lsn The Logical Sector Number
 * @return String formatted as MM:SS:FF
 */
def lsnToCueTimestamp (Integer lsn) {
    // Ensure LSN isn't negative (pre-gap sectors can sometimes be negative, 
    // but cue sheet absolute timestamps start at 0 -- always ignoring the 150 frame lead in)
    int totalSectors = Math.max(0, lsn)
    
    int framesPerSecond = 75
    int secondsPerMinute = 60
    int framesPerMinute = framesPerSecond * secondsPerMinute // 4500
    
    int minutes = totalSectors / framesPerMinute
    int seconds = (totalSectors % framesPerMinute) / framesPerSecond
    int frames  = totalSectors % framesPerSecond
    
    // Format to 2-digit zero-padded strings
    return String.format("%02d:%02d:%02d", minutes, seconds, frames)
}

// creates a filename based on format list and metadata
String createFilename (Map filenameFormats, Map albumMap) {
    String format = (albumMap.album_artist =~ /Various Artists/) ? 'va' : ''
    format += (albumMap.totaldiscs == '1') ? 'standard' : 'multidisc'

    String fileName = ''
    filenameFormats["${format}"].each{
        if (it[0]!='$') {
            fileName += it
        } else {
            String meta = it.drop(1)
            if (albumMap[meta]) {
                fileName += albumMap[meta]
            } else {
                fileName += it
            }
        }
    }
    return fileName
}

// takes data in form of key : value newline and converts to a map
// all strings for values -- if int, then using code needs to convert
Map infoToMap (String info) {
    Map newMap = [:]
    info.tokenize("\n").each {
        if ( ! it =~ /:/) return  // skip lines that don't follow expected pattern
        kvTuple = it.split(':', 2)
        newMap[kvTuple[0].trim()] = kvTuple[1].trim()
        }
    return newMap
}

// use the info returned from cyanrip -I to create the opening part of a cue file
// This won't be enough to add the replay gain info yet
// And, there are elements of the album that are only exposed in the track output.
// so need to pull data from track01 -- otherwise there needs to be convoluted code in the track handling
String albumToCue (Map albumMap) {
    cueHeader = """
        REM COMMENT "${albumMap.cueComment}"
        REM MUSICBRAINZ_ID "${albumMap.'DiscID'}"
        REM DISCID "${albumMap.'CDDB ID'}"
        REM MEDIA "${albumMap.media}"
        REM COMMENT "${albumMap.cyanrip}"
        REM DATE "${albumMap.date}"
        REM MUSICBRAINZ_ALBUMID "${albumMap.musicbrainz_albumid}"
        REM BARCODE "${albumMap.barcode}"
        REM PACKAGING "${albumMap.packaging}"
        REM COUNTRY "${albumMap.country}"
        REM RELEASESTATUS "${albumMap.releasestatus}"
        REM CATALOGNUMBER "${albumMap.catalognumber}"
        REM LABEL "${albumMap.label}"
        REM TOTALDISCS "${albumMap.'Total discs'}"
        REM DISC "${albumMap.'Disc number'}"
        REM FORMAT "${albumMap.format}"
        CATALOG ${albumMap.'Disc MCN'}
        PERFORMER "${albumMap.'Album artist'}"
        TITLE "${albumMap.'Album'}"
        FILE "${albumMap.fileName}" WAVE
        """.stripIndent()
    return cueHeader
}

def tracksToCue (String tracksInfo) {
    Matcher matchTracks = tracksInfo =~ /(?s)(\n|^)Track \d+ info:.+?(?=\nTrack|$)/
    if ( ! matchTracks.find() ) {
        println "error - no match for tracks"
        System.exit(1)
    }
    String cueTracks = ''
    matchTracks.each { track ->
        trackMap = trackExtract(track.join("\n"))
        cueTracks += trackToCue(trackMap)
    }
    return cueTracks
}

def trackExtract(String trackInfo, String trackNo = '\\d+') {
    Matcher matchTrack = trackInfo =~ /(?s)Track\s+($trackNo)\s+info:.+Properties[^\n]*\n(.+?)\n\s+Metadata[^\n]*\n(.+?)\n\s+Embedded/
    if (! matchTrack.find()) {
        println "error -- could not match a track:\n$trackInfo"
        System.exit(1)
    }
    String trackNumber = sprintf('%02d', matchTrack.group(1).toInteger())
    Map properties = infoToMap(matchTrack.group(2))
    Map metadata = infoToMap(matchTrack.group(3))
    Map trackMap = [trackNumber: trackNumber,
        properties: properties,
         metadata: metadata]
    return trackMap
}

String trackToCue (Map trackMap) {
    String trackNumber = trackMap.trackNumber
    Map properties = trackMap.properties
    Map metadata   = trackMap.metadata

    String index00 = ''
    if (properties['Pregap LSN'] != 'none') {
        def pregapLSN = ("${properties['Pregap LSN']}" =~ /^\d+/)
        if (pregapLSN.find()) {
            def pregap = lsnToCueTimestamp(pregapLSN[0].toInteger())
            index00 = "INDEX 00 ${pregap}"
        }
    }
    String isrc = (metadata.isrc)
    
    String index01 = "INDEX 01 " + lsnToCueTimestamp(properties['Start LSN'].toInteger())
    String trackListing = """
        |  TRACK ${trackNumber} AUDIO
        |    TITLE "${metadata.title}"
        |    PERFORMER "${metadata.artist}"
        |    REM mbid "${metadata.mbid}"
        |    ${(metadata.isrc) ? "REM ISRC ${metadata.isrc}" : ''}
        |    ${index00}
        |    ${index01}
    """.stripMargin()
    // seems like some cues include REM ISRC with an unquoted value -- but not available with -I or seemingly -J
    return trackListing
}

// if reading from cd use these parameters as a starting point
List cyanripInfo = [cyanrip, '-s' ,offset, '-I']

// simplistic command line option -if file given then read from named file, otherwise CD
// will want to obtain many info files for testing
String cdInfo =  infoFile ?
                    (new File(infoFile)).text :
                    cyanripInfo.execute().text

// turn into multiple releases found function - or combine with above as an enhanced get info function
if (cdInfo =~ /Multiple releases found/){
    println cdInfo
    releases = cdInfo =~ /(?s)\s+\d+\s+\(ID[^\n]+?\):\s([^\n]+)/
    printf "Enter an index number (not an ID): "
    def input = System.console().readLine()
    index = (input =~ /\d+/)
    if (index) {
        // verify that index is within the bounds of the returned list - probably more trouble than it was worth
        // if someone selects a non-sane value an attempt is made, but return of "Invalid release index" is displayed
        // but why allow them to do so?
        releaseCount = releases.size().toInteger()
        range = 1..releaseCount
        indexNumber = index[0].toInteger()
        if ( indexNumber in range ) {
            println "Getting Musicbrainz data using index ${indexNumber} - ${releases[indexNumber -1][1]}"
            cyanripInfo += ['-R', indexNumber]
            cdInfo = cyanripInfo.execute().text
        } else {
            println "You selected a number (${indexNumber}) that is out of range."
            System.exit(1)
        }
    } else {
        println "No integer value selected"
        System.exit(1)
    }
}

// turn into parse info function + create cue function
Matcher matcher = (cdInfo =~/(?s)\ncyanrip\s([^\s]+)\s[^\n]+\n(.*)\nGaps:\n(.*)\nCover art.*Tracks:\n(.*)$/)
if (matcher.find()) {
    cyanripVersion = matcher.group(1).toString()
    albumInfo = matcher.group(2)
    gapInfo = matcher.group(3) // not sure it is needed yet
    tracksInfo = matcher.group(4)

    albumMap = infoToMap albumInfo
    //gapMap   =  gapInfo  -- gap info is not in key value format -- but not sure it is needed
    track01Map = trackExtract(tracksInfo, '0?1').metadata

    albumMap << track01Map  // give the albumMap all of the data that it should have but is buried in track info output
    // plus some additional metadata
    albumMap.cyanrip = "cyanrip ${cyanripVersion}"
    albumMap.year = extractYear(albumMap.date)  /// set any metadata needed prior to createFilename (e.g. genre, etc.)
    albumMap.fileName = createFilename(filenameFormats, albumMap)
    albumMap.cueComment = cueComment

    /*albumMap.each {tuple ->
        println "${tuple.key} = ${tuple.value}"
    }*/

    String cueHeader = albumToCue(albumMap)
    String cueTracks = tracksToCue(tracksInfo)

    String cueText =  (cueHeader + cueTracks)
    // remove blank lines
    String noBlanksCue = cueText.split('\n').findAll { it.trim() }.join('\n')
    // remove lines ending in null or "null"
    // this avoids having to test for every line to see if metadata exists
    // could elvis operator all calls to set a value other than null, so match is very explicit
    String printableCue = noBlanksCue.split('\n').findAll { (! (it =~ /\s(null|"null")$/)) ? it : '' }.join('\n') 

    File cue = new File(cueFile)
    cue.text = printableCue
} else {
    println 'match not found'
}