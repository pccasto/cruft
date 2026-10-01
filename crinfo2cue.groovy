#! /usr/bin/env groovy
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
//import groovy.lang.GroovyClassLoader

// Load the class dynamically from the file system
//GroovyClassLoader classLoader //
GroovyClassLoader classLoader = new GroovyClassLoader(getClass().classLoader)
Class cruftClass = classLoader.parseClass(new File(System.getProperty('user.home'), '/git/cruft/Cruft.groovy'))
def cruft = cruftClass.newInstance()

// for testing against files, rather than live CD
String infoFileName = (args.length > 0) ? args[0] : null

cruft.logLevel('info')

cruft.cyanrip = '/home/paul/git/cyanrip/build/src/cyanrip' // this provides the -J option, but maybe 0.9.3 is OK
cruft.offset = 6   // this could be set on a per drive basis when multiple drives are available

//cruft.buildCue(infoFile)

//println cruft.cue.sheet

//cruft.cyanRip.rip()
//cruft.tempDirPath = '/tmp/cruft-3429498391152099212'
String workingDir = '/Users/paul/Music/temp/tapestry'
cruft.workingDirPath = workingDir
cruft.util.makeWorkingDir()
cruft.cyanInfo.infoFile = new File("${workingDir}/info.txt")
cruft.cyanInfo.retrieveInfoText()

//println cruft.cyanInfo.infoFile.text
//String t = cruft.cyanInfo.infoText

//println t

cruft.cyanInfo.parseInfoText()
//println cruft.albumMap.toString().replaceAll(', ', '\n')

 //cruft.tracksMapList[0].each{tuple -> println tuple.value}
cruft.log.info cruft.tracksMapList[0].trackProp.toString()
cruft.log.info cruft.tracksMapList[0].trackMeta.toString()

cruft.cue.makeSheet()
println cruft.cue.sheet

//cruft.ripCD(infoFile)

//ripper = cruft.cyanRip.rip()
//ripper.rip
