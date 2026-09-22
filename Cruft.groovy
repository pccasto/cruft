#! /usr/bin/groovy
/*
Copyright (c) Paul C. Casto
Released under MIT license
This file is part of CyanRip Unified File Tools (cruft)
*/

// needed by calling code

//package com.pccasto

import java.util.regex.Matcher
import java.time.Year
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
// import groovy.lang.Tuple
import groovy.io.FileType

import java.io.OutputStream
import java.io.PrintStream

import java.nio.file.Files
import java.nio.file.Paths
 
class Cruft {

    // calling code can override these from command line or config file.
    // offset, outputDir and filenameFormats are the ones most likely to need to be set by the user

    String cyanrip    = '/usr/bin/cyanrip '  // allow for local builds
    // defined in case they are not in the path, or specific versions to be used
    String ffmpeg = 'ffmpeg'
    String metaflac = 'metaflac'

    Integer offset    = null                 // this could be set on a per drive basis when multiple drives are available
    String cueComment = "Created by CRUFT"   // advertisement for now :-)
    String outputType = 'flac'               // currently only supported type

    String workingPath = '/tmp' // to allow user set the tempDir area to something other than /tmp (say a ramdisk)
    File tempDir
    String tempDirPath

    // this approach avoids 'eval' but even so, could be subject to abuse
    // if any of these values (taken from metadata) could be manipulated upstream, then bad things could happen.
    // e.g. conceptually if album_artist were 'bad guys; rm *' and a careless mkdir outputDir were issued, then boom.
    // This code will pass arrays, rather than strings to avoid shell expansion, but need to review any possible holes.
    // the $ in front is not strictly needed -- but deconflicts in cases where text term collides with metadata 
    // e.g. to express something like (disc 1 of 2)
    List outputDirFormat = ['/mnt/Media/Music/IMAGES/', '$album_artist']

    Map filenameFormats = [
        standard    : ['$album_artist',   ' - (', '$year', ') ', '$album',                                       '.', outputType ],
        multidisc   : ['$album_artist',   ' - (', '$year', ') ', '$album',' [','$disc', '/', '$totaldiscs', ']', '.', outputType ],
        vastandard  : ['Various Artists', ' - (', '$year', ') ', '$album',                                       '.', outputType ],
        vamultidisc : ['Various Artists', ' - (', '$year', ') ', '$album',' [','$disc', '/', '$totaldiscs', ']', '.', outputType ]
    ]

    // End of settable variables ---------------------------------

    // probably read only, but maybe calling code could build its own
    String  sheet         = null        // set by Cue / buildCue
    Map     albumMap      = [:]         // from -I set by parse album + track 1 info
    List    tracksMapList = []          // from -I set by parse array of track maps
    Integer releaseID     = 1           // mbz release id, unless another one selected
    String  cueFile       = "cruft.cue" // temp cue for merger
    String  frontImage    = "Front.jpg" // set by cyanrip
    String  backImage     = "Back.jpg"  // set by cyanrip


    // subclasses - not all may be needed, but by declaring here
    // they can use & manipulate cruft instance variables
    // this allows a calling program to use methods that otherwise 
    // would (should?) be wrapped at the top level
    //
    // if they were not inner classes, and use the 'extends Cruft' in their definition,
    // they might have the same capability, but inter + 'extends Cruft' is a Stack Overflow
    Cue         cue =      new Cue()
    CyanripInfo cyanInfo = new CyanripInfo()
    CyanripRip  cyanRip  = new CyanripRip()
    Util        util =     new Util()
    Merger      merger =   new Merger()
    Tagger      tagger =   new Tagger()


    // MBZ specific utilities-------------------
    // get the year as a string from MBZ date string
    static String extractYear (String dateStr) {
println dateStr
        // what date patterns will be sent frm MBZ ?
        List patterns  = ["yyyy-MM-dd", "yyyy", "dd/MM/yyyy", "MMM dd, yyyy", "MM-dd-yyyy"]
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

    // handle the case when MBZ returns multiple matches
    Integer chooseFromMultiple (String infoText) {
        println infoText
        Matcher releases = infoText =~ /(?s)\s+\d+\s+\(ID[^\n]+?\):\s([^\n]+)/
        printf "Enter an index number (not an ID): "
        String input = System.console().readLine()
        Matcher index = (input =~ /\d+/)
        Integer indexSelected
        if (index) {
            // verify that index is within the bounds of the returned list - probably more trouble than it was worth
            // if someone selects a non-sane value an attempt is made, but return of "Invalid release index" is displayed
            // but why allow them to do so?
            Integer releaseCount = releases.size().toInteger()
            Range range = 1..releaseCount
            indexSelected = index[0].toInteger()
            if ( indexSelected in range ) {
                println "Getting Musicbrainz data using index ${indexSelected} - ${releases[indexSelected -1][1]}"
                releaseID = indexSelected
            } else {
                println "You selected a number (${indexSelected}) that is out of range."
                System.exit(1)
            }
        } else {
            println "No integer value selected"
            System.exit(1)
        }
        return indexSelected
    }
    //-----------------------------------------

    // General Utilities-----------------------
    class Util {
        /**
        * Converts a CD Logical Sector Number (LSN/LBA) into a Cue Sheet timestamp string (MM:SS:FF).
        * @param lsn The Logical Sector Number
        * @return String formatted as MM:SS:FF
        */
        static String lsnToCueTimestamp (Integer lsn) {
            // Ensure LSN isn't negative (pre-gap sectors can sometimes be negative, 
            // but cue sheet absolute timestamps start at 0 -- always ignoring the 150 frame lead in)
            Integer totalSectors = Math.max(0, lsn)
            
            Integer framesPerSecond = 75
            Integer secondsPerMinute = 60
            Integer framesPerMinute = framesPerSecond * secondsPerMinute // 4500
            
            Integer minutes = totalSectors / framesPerMinute
            Integer seconds = (totalSectors % framesPerMinute) / framesPerSecond
            Integer frames  = totalSectors % framesPerSecond
            
            // Format to 2-digit zero-padded strings
            return String.format("%02d:%02d:%02d", minutes, seconds, frames)
        }

        // takes data in form of key : value newline and converts to a map
        // all strings for values -- if int, then using code needs to convert
        // could generalize to allow for different tuple internal and external delimiters...
        static Map infoToMap (String info) {
            Map newMap = [:]
            info.tokenize("\n").each {
                if ( ! it =~ /:/) return  // skip lines that don't follow expected pattern
                List kvTuple = it.split(':', 2)
                newMap[kvTuple[0].trim()] = kvTuple[1].trim()
                }
            return newMap
        }


        // this is a Util like method, but does not behave well as a static
        String procRunner (List cmdList, String procName = null) {

            // Save the original stdout so we can still print to the console
            PrintStream originalOut = System.out

            String procText = ''

            try {
                if (procName) {
                    println "${procName} process starting"
                }
                // Create a custom filter stream - tailored for cyanrip ripping output
                //  to handle the 'Ripping and encoding' to overwrite, rather than scroll!
                // AND to tee the output into the procText variable
                OutputStream filterStream = new OutputStream() {
                    StringBuilder buffer = new StringBuilder()

                    @Override
                    void write(int b) throws IOException {
                        buffer.append((char) b)
                        // Flush and replace when a newline is encountered
                        if (b == '\n') {
                            flushBuffer()
                        }
                    }

                    void flushBuffer() {
                        String line = buffer.toString()
                        // Define replaceAll logic 
                        String modifiedLine = line
                        // could make this a switch, depending on other needs 
                        // - fragile, in that name controls this, rather than explicit filter
                        // filter should be set by calling code, not by predetermined hard-coded filter in procRunner
                        if (procName == 'cyanrip rip') {
                            if (line =~ /Ripping and encoding/) {
                                // no need to log
                                modifiedLine = line.replaceFirst(~/(Ripping and encoding[^\n]+)\n/, "\r"+'$1')
                            } else {
                                procText += line
                            }
                        } else {
                            procText += line
                        }
                        originalOut.print(modifiedLine)
                        // ugly, but effective way to deal with .setLength(0) issue with ancient groovy/newer jvm
                        buffer = new StringBuilder()
                    }
                }

                System.setOut(new PrintStream(filterStream))
                StringBuilder stderr = new StringBuilder()

                def proc = cmdList.execute()
                // stream stdout as we go, rather than wait & print
                // better for the long-running actions
                // there are some processes that send a lot to stderr, even without error
                // only show if error -- unless we make that contingent on procName
                proc.consumeProcessOutput(System.out, stderr)
                proc.waitFor()
                if ((proc.exitValue() != 0) && stderr) {
                    println "Error: ${stderr}"
                }
                if (procName) {
                    println "${procName} process finished with exit code: ${proc.exitValue()}"
                }
                return procText // calling code can examine & operate based on text // could return map of exit code, stdout, stderr.
            } finally {
                System.setOut(originalOut)
            }
        }
    
        File createTempDir(){
            if (tempDirPath) { /// allow user to set it to existing path
                tempDir = new File (tempDirPath)
                // but verify it will work
                if (tempDir.exists()) {
                    if (! tempDir.isDirectory()) {
                        // or throw an error
                        println "The path exists but it is not a directory."
                        System.exit(1)
                    }
                } else {
                    println "creating ${tempDirPath}"
                    tempDir.mkdirs()
                    // or throw an error...
                }

            } else {
                workingPath = workingPath ? workingPath : '/tmp'  //groovy 2.x does not have ?=
                def customParent = Paths.get(workingPath)
                tempDir = (Files.createTempDirectory(customParent, "cruft-").toFile())
                tempDirPath = tempDir.absolutePath // be explicit
                println "The temporary directory has been created at: ${tempDirPath}"
            }
            return tempDir

            // to make the temp dir non persistent 
            // -- will default to clear on exit w/ cmd line switch
            // but for now leave the dir for inspection during initial coding efforts
            // not sure this needs the toFile()
                //tempDir.toFile().deleteOnExit() 
                // addShutdownHook { ... }
        }
        
        // lots of files to be written to the tempdir, so have a method call for consistency
        String tempAbsolutePath(String filename){
            return Paths.get(tempDirPath, filename)
        }
 
    }
    
    //-----------------------------------------


    // creates a filename based on format list and album metadata
    String createFilename () {
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

    class Cue {
        String sheet = ''

        // use the info returned from cyanrip -I (or the logs?) to create a cue sheet
        // this really could be any source of metadata... put into Map form
        String build(Map albumMap, List tracksMapList) {
            String cueHeader = albumToCue(albumMap)
            String cueTracks = ''
            tracksMapList.each{ trackMap ->
                cueTracks += trackToCue(trackMap)
            }
            String cueText =  (cueHeader + cueTracks)
            // remove blank lines
            String noBlanksCue = cueText.split('\n').findAll { it.trim() }.join('\n')
            // remove lines ending in null or "null"
            // this avoids having to test for every line to see if metadata exists
            // could elvis operator all calls to set a value other than null, so match is very explicit
            sheet = noBlanksCue.split('\n').findAll { (! (it =~ /\s(null|"null")$/)) ? it : '' }.join('\n')
            return sheet
        }

        // There are elements of the album that are only exposed in the track output.
        // so need to pull data from track01 -- otherwise there needs to be convoluted code in the track handling
        // calling code is responsible for building the albumMap with all the needed info for the header.
        String albumToCue (Map albumMap) {
            String cueHeader = """
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

        // use the info returned from cyanrip -I (or the logs?) to create a track entry
        String trackToCue (Map trackMap) {
            String trackNumber = trackMap.trackNumber
            Map properties = trackMap.properties
            Map metadata   = trackMap.metadata

            String index00 = ''
            if (properties['Pregap LSN'] != 'none') {
                Matcher pregapLSN = ("${properties['Pregap LSN']}" =~ /^\d+/)
                if (pregapLSN.find()) {
                    String pregap = Util.lsnToCueTimestamp(pregapLSN[0].toInteger())
                    index00 = "INDEX 00 ${pregap}"
                }
            }
        
            String index01 = "INDEX 01 " + Util.lsnToCueTimestamp(properties['Start LSN'].toInteger())
            String trackListing = """
                |  TRACK ${trackNumber} AUDIO
                |    TITLE "${metadata.title}"
                |    PERFORMER "${metadata.artist}"
                |    REM mbid "${metadata.mbid}"
                |    REM ISRC ${metadata.isrc}
                |    ${index00}
                |    ${index01}
            """.stripMargin()
            return trackListing
        }
    }

    // Based on cyanrip -I output formats---------------------
    // Could be adjusted to read same data from log file
    class CyanripInfo {
        String infoText = ''

        List tracksToMapList (String tracksInfo) {
            Matcher matchTracks = tracksInfo =~ /(?s)(\n|^)Track \d+ info:.+?(?=\n\s*File\(s\)|$)/
            if ( ! matchTracks.find() ) {
                println "error - no match for tracks"
                System.exit(1)
            }
            String cueTracks = ''
            matchTracks.each { track ->
                println track[0]
                tracksMapList << trackToMap(track.join("\n"))
            }
            System.exit(1)
            return tracksMapList
        }

        Map trackToMap (String trackInfo, String trackNo = '\\d+') {
            Matcher matchTrack = trackInfo =~ /(?s)Track\s+($trackNo)\s+info:.+Properties[^\n]*\n(.+?)\n\s+Metadata[^\n]*\n(.+?)\n.+?/
            if (! matchTrack.find()) {
                println "error -- could not match a track:\n$trackInfo"
                System.exit(1)
            }
            String trackNumber = sprintf('%02d', matchTrack.group(1).toInteger())
            Map properties = Util.infoToMap(matchTrack.group(2))
            Map metadata = Util.infoToMap(matchTrack.group(3))
            Map trackMap = [trackNumber: trackNumber,
                properties: properties,
                metadata: metadata]
            return trackMap
        }

        Void getInfoText (String infoFile = null) {
            // if reading from cd use these parameters as a starting point
            List cyanripInfo = [cyanrip, '-s' , offset, '-I', '-D', tempDirPath, '-U'] // don't need offset here...

            // simplistic command line option -if file given then read from named file, otherwise CD
            // will want to obtain many info files for testing
            infoText =  infoFile ?
                                (new File(infoFile)).text :
                                util.procRunner(cyanripInfo, 'cyanrip info')

            // this test could be skipped if reading from a file, 
            // but for testing with a file that shows multiple, leave in
            if (infoText =~ /Multiple releases found/){
                chooseFromMultiple(infoText) // this should become an mbz static method
                cyanripInfo += ['-R', releaseID]
                infoText = util.procRunner(cyanripInfo, 'cyanrip info')
            }
            return null
        }

        // builds albumMap and tracksMapList for later use
        Void parseInfoText (String infoText = infoText) {
            // turn into try/catch
            // could be used to lookup offset for multi-drive machines
            // could be put in the cue info
            Matcher drive = (infoText =~/(?<=CDROM sensed:\s*)(.*)/)
            String driveInfo = ''
            if (drive.find()) {
                driveInfo = drive.group(1).trim().replaceAll(/\s+/,'-')
            }

            // this required/expected Cover art -- but now using a flag to not pull it during Info
            // Matcher matcher = (infoText =~/(?s)\ncyanrip\s([^\s]+)\s[^\n]+\n(.*)\nGaps:\n(.*)\nCover art.*Tracks:\n(.*)$/)

            Matcher matcher = (infoText =~/(?s)\ncyanrip\s([^\s]+)\s[^\n]+\n(.*)\nGaps:\n(.*)\n.*?Tracks:\n(.*)$/)
            if (matcher.find()) {
                String cyanripVersion = matcher.group(1).toString()
                String albumInfo = matcher.group(2)
                String gapInfo = matcher.group(3) // not sure it is needed yet
                String tracksInfo = matcher.group(4)

                albumMap = Util.infoToMap albumInfo
                //gapMap   =  gapInfo  -- gap info is not in key value format -- but not sure it is needed
                Map trackOne = trackToMap(tracksInfo, '0?1')
                albumMap <<  trackOne.metadata // give the albumMap all of the data that it should have but is buried in track info output
                // plus some additional metadata
                albumMap.pregap = (trackOne.properties['Start LSN']).toInteger()
                albumMap.cyanrip = "cyanrip ${cyanripVersion}"
                //albumMap.year = Cruft.extractYear(albumMap.date)  /// set any metadata needed prior to createFilename (e.g. genre, etc.)
                albumMap.fileName = createFilename()
                albumMap.cueComment = cueComment

                // could debug with this
                albumMap.each {tuple ->
                    println "${tuple.key} = ${tuple.value}"
                }
                tracksMapList = tracksToMapList(tracksInfo)
                return null
            } else {
                println 'match not found'
            }
        }
    }

    class Tagger {
        def cue2vorbis (String cueFile){
            String vorbisFile = "${cueFile}.vorbis"
            File vorbis = new File(vorbisFile)
            vorbis.text = ''
            cue.eachLine { line ->
                // each does not have a break, but walking past all of the unneeded lines is not that expensive
                if (! (line =~ /^(\s+|FILE)/)) {
                    line = line.replaceFirst(~/^REM\s+/, '')
                    line = line.replaceFirst(~/\s+/,'=')
                    line = line.replaceFirst(~/="/,'=')
                    line = line.replaceFirst(~/"\s*$/,'')
                    // how about REM COMMENT lines -- could be multiples -- how to handle?
                    // https://xiph.org/vorbis/doc/v-comment.html - verify values are legal alphabet
                    vorbis << "${line}\n"
                }
            }
            return vorbisFile
        }

        def tag() {
            println "Tagging file"
            vorbisFile = cue2vorbis(cueFile)
            // build this list based on existence of the front & back jpg
            // this currently assumes front exists
            String front = util.tempAbsolutePath(frontImage)
            String back  = util.tempAbsolutePath(backImage)

            List tagCmd = [metaflac, 
                            '--import-picture-from', "3||Front||${front}",
                            '--import-cuesheet-from', cueFile,
                            "--set-tag-from-file=CUESHEET=${cueFile}",
                            '--import-tags-from', vorbisFile,
                            '-o', "tagged.${outputType}",
                            "merged.${outputType}" ]
            util.procRunner(tagCmd, 'tagging file')
        }
    }

    class Merger {
        // better to put data in file than use pipes for this
        File concatFile

        String collectTracks() {
            println "Collecting file list."
            if (! tempDir){println "Error - no tempDir, so nothing to be done"; System.exit(1)}
            concatFile = new File(util.tempAbsolutePath("concat.txt").toString())

            createPregapTrack() // only needed if pregap exists -- but that check is done in the method

            // Collects files, ignoring directories
            List fileList = []
            // how to put outputType variable in this pattern?
            tempDir.eachFileMatch(FileType.FILES, ~/\d.*\.flac$/) { file ->
                fileList << file
            }
            concatFile.text = ''  // in case this is run a second time
            if (fileList) {
                fileList.sort().each{
                    String fileName = util.tempAbsolutePath(it.name).toString()
                    concatFile << "file '${fileName}'\n"
                }
            }
            println "File list collected at ${concatFile.name}"
            return concatFile
        }

        String concatTracks(String concatFile = concatFile){
            if (! concatFile ){println "Error - no concatenation file, so nothing to be done"; System.exit(1)}
            String mergedOutFile = util.tempAbsolutePath("merged.${outputType}")
            List concatCmd = ['ffmpeg', '-y', '-f', 'concat', '-safe', '0', '-i', 
                                concatFile, '-c:a', outputType, mergedOutFile]
            String proc = util.procRunner(concatCmd, 'Concatenation')
            return proc
        }

        String createPregapTrack(){
            String proc = ''
            if(albumMap.pregap > 0) {
                println "Creating pregap file of ${albumMap.pregap} frames for insertion prior to track 1"
                def pregapMsec = albumMap.pregap*1000/75
                List pregapCmd = [ffmpeg, '-y', '-f', 'lavfi', '-i', 'anullsrc=channel_layout=stereo:sample_rate=44100', 
                            '-t', "${pregapMsec}ms", util.tempAbsolutePath("0 - pregap.${outputType}").toString()]
                proc = util.procRunner(pregapCmd, 'pregap file creation')
            }
            return proc
        }

        Void merge(){
            collectTracks()
            concatTracks()
            return null
        }
    }

    class CyanripRip {
        String rip (String mbzId = releaseID) {
            if (! cue) {buildCue()}
            if (! tempDir) {util.createTempDir()}

            // if reading from cd use these parameters as a starting point
            List cyanripCmd = [cyanrip, '-s' , offset, '-R', mbzId, '-D', tempDirPath, '-G']
            // add output dir, name, etc.
            String proc = util.procRunner(cyanripCmd, 'cyanrip rip')
            return proc
        }
    }

    String buildCue (String infoFile = null ) {
        cyanInfo.getInfoText(infoFile) // could print or save it if interested...
        cyanInfo.parseInfoText()
        String cueString = cue.build(albumMap, tracksMapList)
        return cueString
    }

    void ripCD(){
        buildCue()
        //println cue.sheet
        //cyanRip.rip()
        // this could be a cue method
        File cueFileAbsolute = new File (util.tempAbsolutePath(cueFile))
        cueFileAbsolute.text = cue.sheet

        merger.merge()
        tagger.tag()
        null
    }


}
