#! /usr/bin/groovy
/*
Copyright (c) Paul C. Casto
Released under MIT license
This file is part of CyanRip Unified File Tools (cruft)
*/

/* very much a WIP

several things hard coded for testing
little error checking
several items should be separate procedures
  -- all of the execute() calls should pass through a common function - pass the List, and some text strings!
  -- file name creation is currently a function in crinfo2cue - that needs to be in common code
  -- will have to copy it here until common code class is established

don't know if output is fully sane or complete or accurate (kind of important...)
  -- seems to be sane/complete, based on playing with deadbeef -- need to try others
  -- seems to be accurate, based on metaflac --show-md5sum comparison with an abcde ripped file
     -- so the addition of the silence for the pregap is correct for this CD/file
     -- need to figure out how to deal w HTOA 
  -- so why not just use abcde? don't know ... the AccurateRip, and this code's ability to select the MBZ match?
  -- could also easily add the ability to edit the cue as an interim step after the MBZ and before the rip
  -- probably will keep adding pull requests for the abcde fork, and go back and forth for a while


*/

import groovy.io.FileType

def pregap = 33 // hard coded for testing
def cueFile = './hoth.cue' // hard coded for testing

// defined in case they are not in the path, or specific versions to be used
def ffmpeg = 'ffmpeg'
def metaflac = 'metaflac'

def fileList = []
def dir = new File('.') // this will change to a temp dir
def pwd = dir.absolutePath // be explicit

def type = 'flac' // eventually support others? but need to work on pattern
def files2concat = new File("concat.txt")
files2concat.text = ''  // in case this is run a second time

// create pregap silence file
def stdout = new StringBuilder()
def stderr = new StringBuilder()

println "Creating pregap file of ${pregap} frames for insertion prior to track 1"
pregapMsec = pregap*1000/75
pregapProc = [ffmpeg, '-y', '-f', 'lavfi', '-i', 'anullsrc=channel_layout=stereo:sample_rate=44100', 
                            '-t', "${pregapMsec}ms", "0 - pregap.${type}"].execute()

pregapProc.consumeProcessOutput(stdout, stderr)

pregapProc.waitFor()
if (pregapProc.exitValue() != 0) {
    println "Error: ${stderr}"
} else {
    if (stdout) {println "Output:\n${stdout}"}
}

println "Pregap process finished with exit code: ${pregapProc.exitValue()}\n${pregapProc.text}"

println "Concatenating files."

// Collects only files, ignoring directories
dir.eachFileMatch(FileType.FILES, ~/\d.*\.flac$/) { file ->
    fileList << file
}
if (fileList) {
    fileList.sort().each{
        fileName = (pwd+it).replace('/..','')
        files2concat << "file '${fileName}'\n"
    }
}

def concatProc = ['ffmpeg', '-y', '-f', 'concat', '-safe', '0', '-i', 
                    'concat.txt', '-c:a', type, "first.${type}"].execute()             
concatProc.consumeProcessOutput(stdout, stderr)
concatProc.waitFor()// Blocks the script until the process completes
if (concatProc.exitValue() != 0) {
    println "Error: ${stderr}"
} else {
    if (stdout) {println "Output:\n${stdout}"}
}
println "Concat process finished with exit code: ${concatProc.exitValue()}"

//println "Adding initial pregap as silence"
// need to consider https://mark.himsley.org/FFmpeg/creating_silence.html to create a 0-track before the concat
// this approach seemed to fail spectacularly...
//def pregapProc = ['ffmpeg', '-i', "first.${type}", '-af', "adelay=${pregapMsec}|${pregapMsec}", "second.${type}"].execute()

def cue2vorbis (String cueFile){
    File cue = new File(cueFile)
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

println "Tagging file"
vorbisFile = cue2vorbis(cueFile)
def tagProc = [metaflac, 
                '--import-picture-from', '3||Front||Front.jpg',
                '--import-cuesheet-from', cueFile,
                "--set-tag-from-file=CUESHEET=${cueFile}",
                '--import-tags-from', vorbisFile,
                '-o', "tagged.${type}",
                "first.${type}" ].execute()
tagProc.consumeProcessOutput(stdout, stderr)
tagProc.waitFor()// Blocks the script until the process completes
if (tagProc.exitValue() != 0) {
    println "Error: ${stderr}"
} else {
    if (stdout) {println "Output:\n${stdout}"}
}
println "Tag process finished with exit code: ${tagProc.exitValue()}"

