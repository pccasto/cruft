#! /usr/bin/env groovy
/*
Copyright (c) Paul C. Casto
Released under MIT license
This file is part of CyanRip Unified File Tools (cruft)
*/

package com.github.pccasto.cruft

@Grapes([
   // @Grab(group='org.apache.logging', module='groovy-log4j2', version='5.0.8'), // Match your Groovy version
    @Grab(group='org.apache.logging.log4j', module='log4j-slf4j2-impl', version='2.26.1'),
    @Grab(group='org.apache.logging.log4j', module='log4j-core', version='2.26.1'),
    @Grab(group='org.slf4j', module='slf4j-api', version='2.0.17'),
    @GrabConfig(systemClassLoader=true)
])
import groovy.util.logging.Log4j2
import groovy.util.logging.Slf4j

import org.slf4j.LoggerFactory
import org.apache.logging.log4j.Level
import java.util.logging.LogManager
import org.apache.logging.log4j.core.config.Configurator

import java.util.regex.Matcher
import java.time.Year
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
// import groovy.lang.Tuple
import groovy.io.FileType

//import java.io.OutputStream
//import java.io.PrintStream

import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.Path

// import groovy.transform.CompileStatic
import java.math.RoundingMode

/**
 * cruft - a class for using cyanrip for unified file tasks
 *
 @author Paul Casto
 */

// @CompileStatic
@Slf4j
class Cruft {

    Cruft() {
        logLevel('info') //set a default

        // Register the shutdown hook
        /*
        Runtime.getRuntime().addShutdownHook(new Thread({
            cleanUp()
        }))
        */
    }

    // need a method around this !!!
    void logLevel (String level) {
        try {
            Configurator.setLevel('com.github.pccasto.cruft.Cruft', level);
        } catch (Exception e) {
            // probably won't get here, because called with invalid level the setLevel seems to default to DEBUG
            println "Could not set logging to level ${level}"
            println e
        }
    }

    // calling code can override these from command line or config file.
    // offset, outputDir and filenameFormats are the ones most likely to need to be set by the user
    String cyanrip    = '/usr/bin/cyanrip '  // allow for local builds
    // defined in case they are not in the path, or specific versions to be used
    String ffmpeg     = 'ffmpeg'
    String metaflac   = 'metaflac'

    // this could be set on a per drive basis when multiple drives are available
    Integer offset    = null
    String cueComment = 'Created by CRUFT'   // advertisement for now :-)
    String outputType = 'flac'               // currently only supported type

    String workingPath = '/tmp' // to allow user set the workingDir area to something other than /tmp (say a ramdisk)
    File workingDir
    String workingDirPath

    // this approach avoids 'eval' but even so, could be subject to abuse
    // if any of these values (taken from metadata) could be manipulated upstream, then bad things could happen.
    // e.g. conceptually if album_artist were 'bad guys; rm *' and a careless mkdir outputDir were issued, then boom.
    // This code will pass arrays, rather than strings to avoid shell expansion, but need to review any possible holes.
    // the $ in front is not strictly needed -- but deconflicts in cases where text term collides with metadata
    // e.g. to express something like (disc 1 of 2)
    List outputDirFormat = ['/mnt/Media/Music/IMAGES/', '$album_artist']

    List<String> directoryPortion = ['$album_artist']
    List<String> basePortion = ['$album_artist', ' - (', '$year', ') ', '$album']
    List<String> multiDiskPortion = [' [', '$disc', '/',  '$totaldiscs', ']']
    List<String> outputPortion =  [ '.', outputType ]
    List<String> vaPortion = ['Various Artists']
    List<String> vaBase = vaPortion + basePortion[1..-1]
   // End of settable variables ---------------------------------

    private Map<String,List<String>> filenameFormats = [
        standard    : basePortion + outputPortion,
        multidisc   : basePortion + multiDiskPortion + outputPortion,
        vastandard  : vaBase + outputPortion,
        vamultidisc : vaBase + multiDiskPortion + outputPortion
    ]

    // probably read only, but maybe calling code could build its own
    // several of these belong in the subClasses
    private Map<String,String>  albumMap      = [:]         // from -I set by parse album + track 1 info
    private List<Map>           tracksMapList = []          // from -I set by parse array of track maps
    private Integer             releaseID     = 1           // mbz release id, unless another one selected
    private String              frontImage    = 'Front.jpg' // set by cyanrip
    private String              backImage     = 'Back.jpg'  // set by cyanrip

    // subclasses - not all may be needed, but by declaring here
    // they can use & manipulate cruft instance variables
    // this allows a calling program to use methods that otherwise
    // would (should?) be wrapped at the top level
    //
    // if they were not inner classes, and use the 'extends Cruft' in their definition,
    // they might have the same capability, but inter + 'extends Cruft' is a Stack Overflow
    // these could become private if there are class level wrappers to take all needed actions
    private Musicbrainz mbz =      new Musicbrainz()
    private Cue         cue =      new Cue()
    private CyanripInfo cyanInfo = new CyanripInfo()
    private CyanripRip  cyanRip  = new CyanripRip()
    private Util        util =     new Util()
    private Merger      merger =   new Merger()
    private Tagger      tagger =   new Tagger()

    // MBZ specific utilities-------------------
    class Musicbrainz {
        // get the year as a string from MBZ date string
        static String extractYear(String dateStr) {
            if (!dateStr) { return 'none' }
            // what date patterns will be sent frm MBZ ?
            List patterns  = ['yyyy-MM-dd', 'yyyy', 'dd/MM/yyyy', 'MMM dd, yyyy', 'MM-dd-yyyy']
            for (String pattern : patterns) {
                try {
                    DateTimeFormatter formatter = DateTimeFormatter.ofPattern(pattern)
                    return Year.parse(dateStr, formatter)
                } catch (DateTimeParseException ignored) {
                // Try the next pattern
                }
            }
            throw new IllegalArgumentException("Unable to parse date: $dateStr")
        }

        // allow the user to select when MBZ returns multiple matches
        Integer chooseFromMultiple(String infoText) {
            log.debug infoText
            Matcher releases = (infoText =~ /(?s)\s+\d+\s+\(ID[^\n]+?\):\s([^\n]+)/)
            printf 'Enter an index number (not an ID): '
            String input = System.console().readLine()
            Matcher index = (input =~ /\d+/)
            Integer indexSelected
            if (index) {
                // verify that index is within the bounds of the returned list - probably more trouble than it was worth
                // if someone selects a non-sane value an attempt is made, return "Invalid release index" is displayed
                // but why allow them to do so?
                Integer releaseCount = releases.size().toInteger()
                Range range = 1..releaseCount
                String idMatch = index[0]
                indexSelected = idMatch.toInteger()
                if (indexSelected in range) {
                    Integer indexToGroup = indexSelected - 1
                    log.info "Getting Musicbrainz data with index ${indexSelected} - ${releases.group(indexToGroup)[1]}"
                } else {
                    errorExit("You selected a number (${indexSelected}) that is out of range.")
                }
            } else {
                errorExit('No integer value selected')
            }
            return indexSelected
        }
    }
    //-----------------------------------------

    // General Utilities-----------------------
    class Util {
        /**
        * Converts a CD Logical Sector Number (LSN/LBA) into a Cue Sheet timestamp string (MM:SS:FF).
        * @param lsn The Logical Sector Number
        * @return String formatted as MM:SS:FF
        */
        static String lsnToCueTimestamp(Integer lsn) {
            // Ensure LSN isn't negative (pre-gap sectors can sometimes be negative,
            // but cue sheet absolute timestamps start at 0 -- always ignoring the 150 frame lead in)
            Integer totalSectors = Math.max(0, lsn)

            Integer framesPerSecond = 75
            Integer secondsPerMinute = 60
            Integer framesPerMinute = framesPerSecond * secondsPerMinute // 4500

            Integer minutes = ((totalSectors / framesPerMinute).setScale(0, RoundingMode.DOWN)).toInteger() // trunc() in 2.5+
            Integer seconds = (((totalSectors % framesPerMinute) / framesPerSecond).setScale(0, RoundingMode.DOWN)).toInteger()
            Integer frames  = (totalSectors % framesPerSecond)

            // Format to 2-digit zero-padded strings
            return String.format('%02d:%02d:%02d', minutes, seconds, frames)
        }

        // takes data in form of key : value newline and converts to a map
        // all strings for values -- if int, then using code needs to convert
        // could generalize to allow for different tuple internal and external delimiters...
        static Map<String,String> infoToMap(String info) {
            Map<String,String> newMap = [:]
            info.tokenize('\n').each { infoLine ->
                if (!(infoLine =~ /:/)) { return }  // skip lines that don't follow expected pattern
                String[] kvTuple = infoLine.split(':', 2)
                newMap[kvTuple[0].trim()] = kvTuple[1].trim()
            }
            return newMap
        }

        // this is a Util like method, but does not behave well as a static
        String procRunner(String[] cmdList, String procName = null) {
            String procText = ''

            // Save the original stdout so we can still print to the console
            PrintStream originalOut = System.out

            try {
                if (procName) {
                    log.info "${procName} process starting"
                }
                log.debug "Running command: ${cmdList.join(' ')}"
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
                        String line = buffer
                        // Define replaceAll logic
                        String modifiedLine = line
                        // could make this a switch, depending on other needs
                        // - fragile, in that name controls this, rather than explicit filter
                        // filter should be set by calling code, not by predetermined hard-coded filter in procRunner
                        if (procName == 'cyanrip rip') {
                            if (line =~ /Ripping and encoding/) {
                                // no need to log
                                modifiedLine = line.replaceFirst(~/(Ripping and encoding[^\n]+)\n/, '\r' + '$1')
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

                System.out = new PrintStream(filterStream)
                StringBuilder stderr = new StringBuilder()

        Process proc = ['ls'].execute() // cmdList.execute()
                // stream stdout as we go, rather than wait & print
                // better for the long-running actions
                // there are some processes that send a lot to stderr, even without error
                // only show if error -- unless we make that contingent on procName
                proc.consumeProcessOutput(System.out, stderr)
                proc.waitFor()
                if ((proc.exitValue() != 0) && stderr) {
                    log.error "Error: ${stderr}"
                }
                if (procName) {
                    log.info "${procName} process finished with exit code: ${proc.exitValue()}"
                }
                // could return map of exit code, stdout, stderr; but currently only text
                return procText // calling code can examine & operate based on text
            } finally {
                System.out = originalOut
            }
        }

        File makeWorkingDir() {
            if (workingDirPath) { /// allow user to set it to existing path
                workingDir = new File(workingDirPath)
                // but verify it will work
                if (workingDir.exists()) {
                    if (!workingDir.directory) {
                        // or throw an error
                        errorExit("The path ${workingDirPath} exists but it is not a directory.")
                    }
                } else {
                    log.info "Creating ${workingDirPath}"
                    workingDir.mkdirs()
                // or throw an error...
                }
            } else {
                workingPath = workingPath ? workingPath : '/tmp'  //groovy 2.x does not have ?=
                Path customParent = Paths.get(workingPath)

                workingDir = (Files.createTempDirectory(customParent, 'cruft-').toFile())
                workingDirPath = workingDir.absolutePath // be explicit
                log.info "The temporary directory has been created at: ${workingDirPath}"
            }
            return workingDir

            // to make the temp dir non persistent
            // -- will default to clear on exit w/ cmd line switch
            // but for now leave the dir for inspection during initial coding efforts
            // not sure this needs the toFile()
            //workingDir.toFile().deleteOnExit()
            // addShutdownHook { ... }
        }

        // lots of files to be written to the workingDir, so have a method call for consistency
        String workingAbsolute(String filename) {
            String absolute = filename
            if (filename[0] != '/') {
                absolute = Paths.get(workingDirPath, filename).toString()
            }
            return absolute
        }

    }

    //-----------------------------------------

    class Cue {

        String sheet = ''
        String cueFileName = 'cruft.cue' // temp cue for merger
        File cueFile
        String cueAbsolute

        String writeFile(String cueFileName = cueFileName) {
            cueAbsolute = util.workingAbsolute(cueFileName)
            if (!sheet) { makeSheet() }
            cueFile = new File(cueAbsolute)
            cueFile.text = sheet
            return cueFile.name
        }

        // use the info returned from cyanrip -I (or the logs?) to create a cue sheet
        // this really could be any source of metadata... put into Map form
        String makeSheet(Map albumMap = albumMap, List<Map> tracksMapList = tracksMapList) {
            String cueHeader = albumToCue(albumMap)
            String cueTracks = ''
            tracksMapList.each { trackMap  ->
                cueTracks += trackToCue(trackMap)
            }
            String cueText =  (cueHeader + cueTracks)
            // remove blank lines
            String noBlanksCue = cueText.split('\n').findAll { line -> line.trim() }.join('\n')
            // remove lines ending in null or "null"
            // this avoids having to test for every line to see if metadata exists
            // could elvis operator all calls to set a value other than null, so match is very explicit
            sheet = noBlanksCue.split('\n').findAll { line -> (line =~ /\s(null|"null")$/) ? '' : line }.join('\n')
            return sheet
        }

        // There are elements of the album that are only exposed in the track output.
        // so need to pull data from track01 -- otherwise there needs to be convoluted code in the track handling
        // calling code is responsible for building the albumMap with all the needed info for the header.
        private String albumToCue(Map albumMap) {
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
        private String trackToCue(Map<String,Map> trackMap) {
            Map<String,String> trackProp = trackMap.trackProp
            Map<String,String> trackMeta = trackMap.trackMeta
            Integer trackNumber = trackMeta.track.toInteger()

            String index00 = ''
            // can this go without the if test?
            if (trackProp['Pregap LSN'] != 'none') {
                Matcher pregapLSN = (trackProp.'Pregap LSN' =~ /^\d+/)
                if (pregapLSN.find()) {
                    Integer pregapLSNValue = ((String) pregapLSN[0]).toInteger()
                    String pregap = Util.lsnToCueTimestamp(pregapLSNValue)
                    index00 = "INDEX 00 ${pregap}"
                }
            }

            String index01 = 'INDEX 01 ' + Util.lsnToCueTimestamp(trackProp['Start LSN'].toInteger())
            String trackListing = """
                |  TRACK ${sprintf('%02d', trackNumber)} AUDIO
                |    TITLE "${trackMeta.title}"
                |    PERFORMER "${trackMeta.artist}"
                |    REM mbid "${trackMeta.mbid}"
                |    REM ISRC ${trackMeta.isrc}
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
        File infoFile

        List<Map> tracksToMapList(String tracksInfo) {
            Matcher matchTracks = (tracksInfo =~ /(?s)(\n|^)Track \d+ info:.+?(?=\n\s*File\(s\)|$)/)
            if (!matchTracks.find()) {
                errorExit('no match for tracks')
            }
            matchTracks.each { List track ->
                tracksMapList << trackToMap(track.join('\n'))
            }
            return tracksMapList
        }

        Map<String, Map> trackToMap(String trackInfo, String trackNo = '\\d+') {
            Matcher matchTrack =  (trackInfo =~
            /(?s)Track\s+($trackNo)\s+info:.+?Properties[^\n]*?\n(.+?)\n\s+Metadata[^\n]*\n(.+?)\n+(?=\s*(Embed|File|$))/
            )

            if (!matchTrack.find()) {
                errorExit("Could not match a track (${trackNo}):\n$trackInfo")
            }
            // yes, these intermediate variables are not needed, but helped while developing/troubleshooting...
            //Integer trackNumber = matchTrack.group(1).toInteger()
            Map trackProp = Util.infoToMap(matchTrack.group(2))
            Map trackMeta = Util.infoToMap(matchTrack.group(3))
            Map trackMap = [
                trackProp: trackProp,
                trackMeta: trackMeta
            ]
            return trackMap
        }

        String retrieveInfoText(File infoFile = infoFile) {
            // if reading from cd use these parameters as a starting point
            // the -U may or may not be appropriate here -- TODO - maybe not -U, but -G later
            String[] cyanripInfo =
                [cyanrip, '-s' , offset, '-I', '-D', workingDirPath, '-U'] // don't need offset here...

            // simplistic command line option -if file given then read from named file, otherwise CD
            // will want to obtain many info files for testing
            infoText =  infoFile ?
                                infoFile.text :
                                util.procRunner(cyanripInfo, 'cyanrip info')

            // this test could be skipped if reading from a file,
            // but for testing with a file that shows multiple, leave in
            if (infoText =~ /Multiple releases found/) {
                releaseID = mbz.chooseFromMultiple(infoText)
                cyanripInfo += ['-R', releaseID]
                infoText = util.procRunner(cyanripInfo, 'cyanrip info')
            }
            return infoText
        }

        // builds albumMap and tracksMapList for later use
        void parseInfoText(String infoText = infoText) {
            // turn into try/catch
            // could be used to lookup offset for multi-drive machines
            // could be put in the cue info

            Matcher matcher = (infoText =~ /(?s)\ncyanrip\s([^\s]+)\s[^\n]+\n(.*)\nGaps:\n(.*)\n.*?Tracks:\n(.*)$/)
            if (matcher.find()) {
                String cyanripVersion = matcher.group(1)
                String albumInfo = matcher.group(2)
                //String gapInfo = matcher.group(3) // not sure it is needed yet
                String tracksInfo = matcher.group(4)

                albumMap = Util.infoToMap albumInfo

                //gapMap   =  gapInfo  -- gap info is not in key value format -- but not sure it is needed
                Map trackOne = trackToMap(tracksInfo, '0?1')

                if ( trackOne.trackMeta.track != '1') {
                    System.exit(1)
                }

                // give the albumMap all of the data that it should have but is buried in track info output
                // but ignore the items that really are specific to the track
                Map trackOneMeta = trackOne['trackMeta']
                // trackOneMeta.removeAll { key, value -> (['title', 'artist', 'mbid', 'track'].contains(key)) } // not in 2.4.21...
                Map trackOneFiltered = trackOneMeta.findAll {key, value -> !(['title', 'artist', 'mbid', 'track'].contains(key)) }
                albumMap += trackOneFiltered

                // plus some additional metadata
                albumMap['pregap'] = (String) trackOne.trackProp.'Start LSN'
                albumMap.cyanrip = "cyanrip ${cyanripVersion}"
                // set any metadata needed prior to getFilename (e.g. genre, etc.)
                albumMap.year = Musicbrainz.extractYear((String) albumMap.date)
                albumMap.fileName = albumName()
                albumMap.cueComment = cueComment

                Matcher drive = (infoText =~ /(?<=CDROM sensed:\s*)(.*)/)
                if (drive.find()) {
                    albumMap.driveInfo = drive.group(1).trim().replaceAll(/\s+/, '-')
                    albumMap = albumMap.findAll { key, value ->
                       !(['Drive used', 'Device model'].contains(key))
                    }
                }

                // could debug with this
                log.debug 'Album Map:'
                albumMap.each { tuple ->
                    log.debug "${tuple.key} = ${tuple.value}"
                }
                tracksMapList = tracksToMapList(tracksInfo)
                return null
            }
            errorExit('Match not found')
        }

    }

    class Tagger {

        String taggedFilename = "tagged.${outputType}"
        String vorbisFileName
        File vorbisFile

        String cue2vorbis(String cueAbsolute = cue.cueAbsolute) {
            vorbisFileName = "${cueAbsolute}.vorbis"
            vorbisFile = new File(vorbisFileName)
            vorbisFile.text = ''
            cue.sheet.eachLine { String line
                // each does not have a break, but walking past all of the unneeded lines is not that expensive
                if (!(line =~ /^(\s+|FILE)/)) {
                    String tempLine  = line.replaceFirst(~/\s+/, '=')
                    tempLine = tempLine.replaceFirst(~/="/, '=')
                    tempLine = tempLine.replaceFirst(~/"\s*$/, '')
                    // how about REM COMMENT lines -- could be multiples -- how to handle?
                    // https://xiph.org/vorbis/doc/v-comment.html - verify values are legal alphabet
                    vorbisFile << "${tempLine}\n"
                }
            }
            return vorbisFile
        }

        String tag(String inputFile) {
            log.info 'Tagging file'
            // ? error if vorbisFile does not exist?
            String frontName = util.workingAbsolute(frontImage)
            String backName  = util.workingAbsolute(backImage)
            File front = new File (frontName)
            File back = new File(backName)

            String[] tagCmd = [metaflac]
            if (front.exists()) { tagCmd += [ '--import-picture-from', "3||Front||${frontName}"] }
            if (back.exists())  { tagCmd += [ '--import-picture-from', "4||Back||${backName}"]   }
            tagCmd += [ '--import-cuesheet-from', cue.cueAbsolute,
                            "--set-tag-from-file=CUESHEET=${cue.cueAbsolute}",
                            '--import-tags-from', vorbisFileName,
                            '-o', taggedFilename,
                            inputFile ]
            String procText = util.procRunner(tagCmd, 'tagging file')
            return procText
        }

    }

    class Merger {

        // better to put data in file than use pipes for this
        File concatFile
        String mergedFileName

        String collectTracks() {
            log.debug 'Collecting file list.'
            if (!workingDir) {
                errorExit('No workingDir, so nothing to be done')
            }
            concatFile = new File(util.workingAbsolute('concat.txt'))

            addPregapTrackFile() // only needed if pregap exists -- but that check is done in the method

            // Collects files, ignoring directories
            List<File> fileList = []
            // how to put outputType variable in this pattern?
            workingDir.eachFileMatch(FileType.FILES, ~/\d.*\.flac$/) { file ->
                fileList << file
            }
            concatFile.text = ''  // in case this is run a second time
            if (fileList) {
                fileList.sort().each { file ->
                    String fileName = util.workingAbsolute(file.name)
                    concatFile << "file '${fileName}'\n"
                }
            }
            log.debug "File list collected at ${concatFile.name}"
            return concatFile
        }

        String concatTracks(String concatFile = concatFile) {
            if (!concatFile) {
                errorExit('No concatenation file, so nothing to be done')
            }
            String mergedFileName = util.workingAbsolute("merged.${outputType}")
            String[] concatCmd = ['ffmpeg', '-y', '-f', 'concat', '-safe', '0', '-i',
                                concatFile, '-c:a', outputType, mergedFileName]
            String procText = util.procRunner(concatCmd, 'Concatenation')
            return procText
        }

        String addPregapTrackFile() {
            String procText = ''
            if ((albumMap.pregap).toInteger() > 0) {
                log.info "Creating pregap file of ${albumMap.pregap} frames for insertion prior to track 1"
                Integer pregapMsec = (((albumMap.pregap).toInteger() * 1000) / 75).setScale(0, RoundingMode.HALF_UP).toInteger() //?precise
                String[] pregapCmd =
                    [ffmpeg, '-y', '-f', 'lavfi', '-i', 'anullsrc=channel_layout=stereo:sample_rate=44100',
                            '-t', "${pregapMsec}ms", util.workingAbsolute("0 - pregap.${outputType}")]
                procText = util.procRunner(pregapCmd, 'pregap file creation')
            }
            return procText
        }

        String merge() {
            collectTracks()
            concatTracks()
            return mergedFileName
        }

    }

    class CyanripRip {

        // if reading from cd use these parameters as a starting point
        // calling code could << other args (e.g. -S, etc.)
        // seems like the -G not only doesn't embed image, but also doesn't pull
        // if using -G will have to pull images from info
        String[] cyanripCmd = [cyanrip, '-s' , offset ]

        String rip(String mbzId = releaseID) {
            if (!workingDir) { util.makeWorkingDir() }
            if (!cue) { makeCue() }
            cyanripCmd += ['-R', mbzId, '-D', workingDirPath]

            String proc = util.procRunner(cyanripCmd, 'cyanrip rip')
            return proc
        }

    }

    // creates an album filename based on format list and album metadata
    String albumName() {
        String format = (albumMap.album_artist =~ /Various Artists/) ? 'va' : ''
        format += (albumMap.totaldiscs == '1') ? 'standard' : 'multidisc'

        String fileName = ''
        // change format to be #key# rather than $
        filenameFormats["${format}"].each { String formatElement ->
            if (formatElement[0] != '$') {
                fileName += formatElement
            } else {
                String meta = formatElement.drop(1)
                if (albumMap[meta]) {
                    fileName += albumMap[meta]
                } else {
                    fileName += formatElement
                }
            }
        }
        return fileName
    }

    String makeCue(File infoFile = cyanInfo.infoFile) {
        cyanInfo.retrieveInfoText(infoFile)
        cyanInfo.parseInfoText()
        cue.makeSheet(albumMap, tracksMapList)
        return cue.sheet
    }

    void ripCD() {
        makeCue()
        log.debug cue.sheet // could have a step to allow for editing cue file at this point
        cue.writeFile()
        //cyanRip.rip()
        String mergedFileName = merger.merge()
        //tagger.tag(mergedFileName)
        null
    }

    private void cleanUp(Integer exitCode = 0) {
        /* groovylint-disable-next-line SystemExit */
        System.exit(exitCode)
    }

    private void errorExit(String msg) {
        log.error(msg)
        /* groovylint-disable-next-line SystemExit */
        cleanUp(1)
    }

}
