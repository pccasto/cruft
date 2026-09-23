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
//import groovy.lang.GroovyClassLoader

// Load the class dynamically from the file system

@CompileDynamic
GroovyClassLoader classLoader //
classLoader = new GroovyClassLoader(getClass().classLoader)
Class cruftClass = classLoader.parseClass(new File('/home/paul/git/cruft/Cruft.groovy'))
Cruft cruft = cruftClass.newInstance()

// for testing against files, rather than live CD
String infoFile = (args.length > 0) ? args[0] : null

cruft.cyanrip = '/home/paul/git/cyanrip/build/src/cyanrip' // this provides the -J option, but maybe 0.9.3 is OK
cruft.offset = 6   // this could be set on a per drive basis when multiple drives are available

//cruft.buildCue(infoFile)

//println cruft.cue.sheet

//cruft.cyanRip.rip()
//cruft.tempDirPath = '/tmp/cruft-3429498391152099212'
cruft.tempDirPath = '/mnt/Media/Music/temp/hoth'
cruft.util.createTempDir()

cruft.ripCD(infoFile)

//ripper = cruft.cyanRip.rip()
//ripper.rip
