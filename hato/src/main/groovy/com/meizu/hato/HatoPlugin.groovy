package com.meizu.hato

import com.meizu.hato.util.HatoAndroidUtils
import com.meizu.hato.util.HatoFileUtils
import com.meizu.hato.util.HatoMapUtils
import com.meizu.hato.util.HatoProcessor
import com.meizu.hato.util.HatoSetUtils
import org.apache.commons.codec.digest.DigestUtils
import org.apache.commons.io.FileUtils
import org.gradle.api.Plugin
import org.gradle.api.Project

class HatoPlugin implements Plugin<Project> {
    HashSet<String> includePackage
    HashSet<String> excludeClass
    def debugOn
    def patchList = []
    def beforeDexTasks = []
    private static final String HATO_DIR = "HatoDir"
    private static final String HUATO_PATCHES = "hatoPatches"

    private static final String MAPPING_TXT = "mapping.txt"
    private static final String HASH_TXT = "hash.txt"

    private static final String DEBUG = "debug"


    @Override
    void apply(Project project) {

        project.extensions.create("hato", HatoExtension, project)



        project.afterEvaluate {
            //拿到脚本传过来的过滤包信息
            def extension = project.extensions.findByName("hato") as HatoExtension
            includePackage = extension.includePackage
            excludeClass = extension.excludeClass
            debugOn = extension.debugOn

            project.android.applicationVariants.each { variant ->

                //非debug版本,或者是调试模式
                if (!variant.name.contains(DEBUG) || (variant.name.contains(DEBUG) && debugOn)) {

                    Map hashMap
                    File hatoDir
                    File patchDir

                    def name = variant.name.capitalize();
                    println("variant.name.capitalize()------->${name}")
                    def preDexTask = project.tasks.findByName("preDex${name}")
                    def dexTask = project.tasks.findByName("dex${name}")
                    def proguardTask = project.tasks.findByName("proguard${name}")

                   // def processManifestTask = project.tasks.findByName("process${name}Manifest")
                  //  def manifestFile = processManifestTask.outputs.files.files[0]

                    def oldHatoDir = HatoFileUtils.getFileFromProperty(project, HATO_DIR)

                    if (oldHatoDir) {
                        def mappingFile = HatoFileUtils.getVariantFile(oldHatoDir, variant, MAPPING_TXT)
                        HatoAndroidUtils.applymapping(proguardTask, mappingFile)
                    }
                    if (oldHatoDir) {
                        def hashFile = HatoFileUtils.getVariantFile(oldHatoDir, variant, HASH_TXT)
                        hashMap = HatoMapUtils.parseMap(hashFile)
                    }

                    def dirName = variant.dirName
                    hatoDir = new File("${project.buildDir}/outputs/hato")

                    def outputDir = new File("${hatoDir}/${dirName}")
                    def hashFile = new File(outputDir, "hash.txt")

                    Closure hatoPrepareClosure = {
                       /* def applicationName = HatoAndroidUtils.getApplication(manifestFile)
                        if (applicationName != null) {
                            excludeClass.add(applicationName)
                        }*/

                        outputDir.mkdirs()
                        if (!hashFile.exists()) {
                            hashFile.createNewFile()
                        }

                        if (oldHatoDir) {
                            patchDir = new File("${hatoDir}/${dirName}/patch")
                            patchDir.mkdirs()
                            patchList.add(patchDir)
                        }
                    }

                    def hatoPatch = "hato${variant.name.capitalize()}Patch"
                    project.task(hatoPatch) << {
                        if (patchDir) {
                            HatoAndroidUtils.dex(project, patchDir)
                        }
                    }
                    def hatoPatchTask = project.tasks[hatoPatch]

                    Closure copyMappingClosure = {
                        if (proguardTask) {
                            def mapFile = new File("${project.buildDir}/outputs/mapping/${variant.dirName}/mapping.txt")
                            def newMapFile = new File("${hatoDir}/${variant.dirName}/mapping.txt");
                            FileUtils.copyFile(mapFile, newMapFile)
                        }
                    }


                    if (preDexTask) {     //没有开启Multidex
                        def hatoJarBeforePreDex = "hatoJarBeforePreDex${variant.name.capitalize()}"
                        project.task(hatoJarBeforePreDex) << {
                            Set<File> inputFiles = preDexTask.inputs.files.files
                            inputFiles.each { inputFile ->
                                def path = inputFile.absolutePath
                                println("------->hatoJarBeforePreDex path ${path}" )
                                if (HatoProcessor.shouldProcessPreDexJar(path)) {
                                    HatoProcessor.processJar(hashFile, inputFile, patchDir, hashMap, includePackage, excludeClass)
                                }
                            }
                        }
                        def hatoJarBeforePreDexTask = project.tasks[hatoJarBeforePreDex]
                        hatoJarBeforePreDexTask.dependsOn preDexTask.taskDependencies.getDependencies(preDexTask)
                        preDexTask.dependsOn hatoJarBeforePreDexTask
                        hatoJarBeforePreDexTask.doFirst(hatoPrepareClosure)

                        //before Dex task,
                        def hatoClassBeforeDex = "hatoClassBeforeDex${variant.name.capitalize()}"
                        project.task(hatoClassBeforeDex) << {
                            Set<File> inputFiles = dexTask.inputs.files.files
                            inputFiles.each { inputFile ->
                                def path = inputFile.absolutePath
                                if (path.endsWith(".class") && !path.contains("/R\$") && !path.endsWith("/R.class") && !path.endsWith("/BuildConfig.class")) {
                                    if (HatoSetUtils.isIncluded(path, includePackage)) {
                                        if (!HatoSetUtils.isExcluded(path, excludeClass)) {
                                            def bytes = HatoProcessor.processClass(inputFile)
                                            println("------->hatoClassBeforeDex path ${path}" )
                                            path = path.split("${dirName}/")[1]
                                            def hash = DigestUtils.shaHex(bytes)
                                            hashFile.append(HatoMapUtils.format(path, hash))

                                            if (HatoMapUtils.notSame(hashMap, path, hash)) {
                                                //copy insame class to patch dir
                                                HatoFileUtils.copyBytesToFile(inputFile.bytes, HatoFileUtils.touchFile(patchDir, path))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        def hatoClassBeforeDexTask = project.tasks[hatoClassBeforeDex]
                        hatoClassBeforeDexTask.dependsOn dexTask.taskDependencies.getDependencies(dexTask)
                        dexTask.dependsOn hatoClassBeforeDexTask
                        hatoClassBeforeDexTask.doLast(copyMappingClosure)

                        hatoPatchTask.dependsOn hatoClassBeforeDexTask
                        beforeDexTasks.add(hatoClassBeforeDexTask)
                    } else {    //开启Multidex
                        def hatoJarBeforeDex = "hatoJarBeforeDex${variant.name.capitalize()}"
                        project.task(hatoJarBeforeDex) << {
                            Set<File> inputFiles = dexTask.inputs.files.files    //classes.jar or allclasses.jar
                            inputFiles.each { inputFile ->
                                def path = inputFile.absolutePath
                                if (path.endsWith(".jar")) {
                                    HatoProcessor.processJar(hashFile, inputFile, patchDir, hashMap, includePackage, excludeClass)
                                }
                            }
                        }
                        def hatoJarBeforeDexTask = project.tasks[hatoJarBeforeDex]
                        hatoJarBeforeDexTask.dependsOn dexTask.taskDependencies.getDependencies(dexTask)
                        dexTask.dependsOn hatoJarBeforeDexTask

                        hatoJarBeforeDexTask.doFirst(hatoPrepareClosure)
                        hatoJarBeforeDexTask.doLast(copyMappingClosure)

                        hatoPatchTask.dependsOn hatoJarBeforeDexTask
                        beforeDexTasks.add(hatoJarBeforeDexTask)
                    }

                }
            }

            //generate all patch.jar task
            project.task(HUATO_PATCHES) << {
                patchList.each { patchDir ->
                    HatoAndroidUtils.dex(project, patchDir)
                }
            }
            beforeDexTasks.each {
                project.tasks[HUATO_PATCHES].dependsOn it
            }
        }
    }
}

