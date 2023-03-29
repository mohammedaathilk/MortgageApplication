@groovy.transform.BaseScript com.ibm.dbb.groovy.ScriptLoader baseScript
import java.nio.file.PathMatcher
import java.util.regex.*
import com.ibm.dbb.build.*
import com.ibm.dbb.dependency.*
import com.ibm.dbb.metadata.*
import groovy.transform.*
import java.net.URLEncoder

// define script properties
@Field BuildProperties props = BuildProperties.getInstance()
@Field def gitUtils= loadScript(new File("GitUtilities.groovy"))
@Field def buildUtils= loadScript(new File("BuildUtilities.groovy"))
@Field def impactUtils= loadScript(new File("ImpactUtilities.groovy"))

/**
 * This utilities script is a collection of methods for the reporting 
 * capabilities of zAppBuild - for details see docs/REPORTS.md
 * 
 * This includes
 * 
 *  - Report external impacted files (impacted logical files in other collections)
 *  - Report concurrent changes to document changes in other configurations within the repository 
 * 
 */


// Methods for reporting external impacted files

/**
 * Method to query the DBB collections with a list of files
 * Configured through reportExternalImpacts* build properties
 */

def reportExternalImpacts(Set<String> changedFiles){
	// query external collections to produce externalImpactList

	Map<String,HashSet> collectionImpactsSetMap = new HashMap<String,HashSet>() // <collection><List impactRecords>
	Set<String> impactedFiles = new HashSet<String>()

	List<String> externalImpactReportingList = new ArrayList()

	if (props.verbose) println("*** Running external impact analysis with file filter ${props.reportExternalImpactsAnalysisFileFilter} and collection patterns ${props.reportExternalImpactsCollectionPatterns} with analysis mode ${props.reportExternalImpactsAnalysisDepths}")


	try {

		if (props.reportExternalImpactsAnalysisDepths == "simple" || props.reportExternalImpactsAnalysisDepths == "deep"){

			// get directly impacted candidates first
			if (props.verbose) println("*** Running external impact analysis for files ")

			// calculate and collect external impacts
			changedFiles.each{ changedFile ->

				List<PathMatcher> fileMatchers = buildUtils.createPathMatcherPattern(props.reportExternalImpactsAnalysisFileFilter)

				// check that file is on reportExternalImpactsAnalysisFileFilter
				if(buildUtils.matches(changedFile, fileMatchers)){

					// get directly impacted candidates first
					if (props.verbose) println("     $changedFile ")

					externalImpactReportingList.add(changedFile)
				}
				else {
					if (props.verbose) println("*** Analysis and reporting has been skipped for changed file $changedFile due to build framework configuration (see configuration of build property reportExternalImpactsAnalysisFileFilter)")
				}
			}

			if (externalImpactReportingList.size() != 0) {
				(collectionImpactsSetMap, impactedFiles) = calculateLogicalImpactedFiles(externalImpactReportingList, changedFiles, collectionImpactsSetMap, "***", "buildSet")


				// get impacted files of idenfied impacted files
				if (props.reportExternalImpactsAnalysisDepths == "deep") {
					if (props.verbose) println("**** Running external impact analysis for identified external impacted files as dependent files of the initial set. ")
					impactedFiles.each{ impactedFile ->
						if (props.verbose) println("     $impactedFile ")

					}
					def impactsBin
					(collectionImpactsSetMap, impactsBin) = calculateLogicalImpactedFiles(new ArrayList(impactedFiles), changedFiles, collectionImpactsSetMap, "****", "impactSet")
				}

			}

			// generate reports by collection / application
			collectionImpactsSetMap.each{ entry ->
				externalImpactList = entry.value
				if (externalImpactList.size()!=0){
					// write impactedFiles per application to build workspace
					// naming convention: externalImpacts_<collectionName>.log
					String encodedFileName = URLEncoder.encode("externalImpacts_${entry.key}.log", "UTF-8")
					String impactListFileLoc = "${props.buildOutDir}/${encodedFileName}"
					if (props.verbose) println("*** Writing report of external impacts to file $impactListFileLoc")
					File impactListFile = new File(impactListFileLoc)
					String enc = props.logEncoding ?: 'IBM-1047'
					impactListFile.withWriter(enc) { writer ->
						externalImpactList.each { file ->
							// if (props.verbose) println file
							writer.write("$file\n")
						}
					}
				}
			}

		}
		else {
			println("*! build property reportExternalImpactsAnalysisDepths has an invalid value : ${props.reportExternalImpactsAnaylsisDepths} , valid: simple | deep")
		}

	} catch (Exception e) {
		println("*! (ReportingUtilities.reportExternalImpacts) Exception caught during reporting of external impacts. Build continues.")
		println(e.getMessage())
		println(e.printStackTrace())
	}
}

/*
 * Used to inspect dbb collections for potential impacts, sub-method to reportExternalImpacts
 */

def calculateLogicalImpactedFiles(List<String> fileList, Set<String> changedFiles, Map<String,HashSet> collectionImpactsSetMap, String indentationMsg, String analysisMode) {
	MetadataStore metadataStore = MetadataStoreFactory.getMetadataStore()

	// local matchers to inspect files and collections
	List<Pattern> collectionMatcherPatterns = createMatcherPatterns(props.reportExternalImpactsCollectionPatterns)

	// local
	List<LogicalDependency> logicalDependencies = new ArrayList()
	
	// will be returned
	Set<String> impactedFiles = new HashSet<String>()

	// creating a list logical dependencies
	fileList.each{ file ->
		// go after all the files passed in; assess the identified impacted files to skip analysis for files from an impactSet which are on the changed files
		if(analysisMode.equals('buildSet') || (analysisMode.equals('impactSet') && !changedFiles.contains(file))){
			String memberName = CopyToPDS.createMemberName(file)
			def ldepFile = new LogicalDependency(memberName, null, null);
			logicalDependencies.add(ldepFile)
		}else {
			// debug-output
			// println("$indentationMsg!* Skipped redundant analysis. $file was already or will be procceed soon.")
		}
	}

	if(logicalDependencies.size != 0) {

		// iterate over collections
		metadataStore.getCollections().each{ collection ->
			String cName = collection.getName()
			if(matchesPattern(cName,collectionMatcherPatterns)){ // find matching collection names

				def Set<String> externalImpactList = collectionImpactsSetMap.get(cName) ?: new HashSet<String>()
				// query dbb metadatastore for files with all logicalDependencies
				def logicalImpactedFilesCollections = metadataStore.getImpactedFiles([cName], logicalDependencies);
				
				logicalImpactedFilesCollections.each{ collectionImpacts ->
					List<LogicalFile> logicalImpactedFiles = collectionImpacts.getLogicalFiles()
					logicalImpactedFiles.each{ logicalFile ->
						if (props.verbose) println("$indentationMsg Potential external impact found ${logicalFile.getLname()} (${logicalFile.getFile()}) in collection ${cName} ")
						def impactRecord = "${logicalFile.getLname()} \t ${logicalFile.getFile()} \t ${cName}"
						externalImpactList.add(impactRecord)
						impactedFiles.add(logicalFile.getFile())
					}
					// adding updated record
					collectionImpactsSetMap.put(cName, externalImpactList)
					
				}
				
				

			}
			else{
				// debug-output
				//if (props.verbose) println("$cName does not match pattern: $collectionMatcherPatterns")
			}
		}
	}
	else {
		// debug-output
		//if (props.verbose) println("Empty fileList")
	}


	return [
		collectionImpactsSetMap,
		impactedFiles
	]
}

// Methods for reporting concurrent changes

/**
 * Method to calculate and report the changes between the current configuration and concurrent configurations;
 * leverages the existing infrastructure to calculateChangedFiles - in this case for concurrent configs.
 *
 * Invokes method generateConcurrentChangesReports to produce the reports
 *
 * @param buildSet
 *
 */
def calculateConcurrentChanges(Set<String> buildSet) {
	
		// initialize patterns
		List<Pattern> gitRefMatcherPatterns = createMatcherPatterns(props.reportConcurrentChangesGitBranchReferencePatterns)
	
		// obtain all current remote branches
		// TODO: Handle / Exclude branches from other repositories
		Set<String> remoteBranches = new HashSet<String>()
		props.applicationSrcDirs.split(",").each { dir ->
			dir = buildUtils.getAbsolutePath(dir)
			remoteBranches.addAll(gitUtils.getRemoteGitBranches(dir))
		}
		
		// Run analysis for each remoteBranch, which matches the configured criteria
		remoteBranches.each { gitReference ->
	
			if (matchesPattern(gitReference,gitRefMatcherPatterns) && !gitReference.equals(props.applicationCurrentBranch)){
	
				Set<String> concurrentChangedFiles = new HashSet<String>()
				Set<String> concurrentRenamedFiles = new HashSet<String>()
				Set<String> concurrentDeletedFiles = new HashSet<String>()
				Set<String> concurrentBuildProperties = new HashSet<String>()
	
				if (props.verbose) println "***  Analysing and validating changes for branch $gitReference ."
	
				(concurrentChangedFiles, concurrentRenamedFiles, concurrentDeletedFiles, concurrentBuildProperties) = impactUtils.calculateChangedFiles(null, true, gitReference)
	
				// generate reports and verify for intersects
				generateConcurrentChangesReports(buildSet, concurrentChangedFiles, concurrentRenamedFiles, concurrentDeletedFiles, gitReference)
	
			}
		}
	
	}

/*
 * Method to generate the Concurrent Changes reports and validate if the current build list intersects with concurrent changes
 */

def generateConcurrentChangesReports(Set<String> buildList, Set<String> concurrentChangedFiles, Set<String> concurrentRenamedFiles, Set<String> concurrentDeletedFiles, String gitReference){
	String concurrentChangesReportLoc = "${props.buildOutDir}/report_concurrentChanges.txt"

	File concurrentChangesReportFile = new File(concurrentChangesReportLoc)
	String enc = props.logEncoding ?: 'IBM-1047'
	concurrentChangesReportFile.withWriterAppend(enc) { writer ->

		if (!(concurrentChangedFiles.size() == 0 &&  concurrentRenamedFiles.size() == 0 && concurrentDeletedFiles.size() == 0)) {

			if (props.verbose) println("** Writing report of concurrent changes to $concurrentChangesReportLoc for configuration $gitReference")

			writer.write("\n=============================================== \n")
			writer.write("** Report for configuration: $gitReference \n")
			writer.write("========\n")

			if (concurrentChangedFiles.size() != 0) {
				writer.write("** Changed Files \n")
				concurrentChangedFiles.each { file ->
					if (props.verbose) println " Changed: ${file}"
					if (buildList.contains(file)) {
						writer.write("* $file is changed and intersects with the current build list.\n")
						String msg = "*!! $file is changed on branch $gitReference and intersects with the current build list."
						println msg
						
						// update build result
						if (props.reportConcurrentChangesIntersectionFailsBuild && props.reportConcurrentChangesIntersectionFailsBuild.toBoolean()) {
							props.error = "true"
							buildUtils.updateBuildResult(errorMsg:msg)
						} else {
							buildUtils.updateBuildResult(warningMsg:msg)
						}
					}
					else
						writer.write("  $file\n")
				}
			}

			if (concurrentRenamedFiles.size() != 0) {
				writer.write("** Renamed Files \n")
				concurrentRenamedFiles.each { file ->
					if (props.verbose) println " Renamed: ${file}"
					if (buildList.contains(file)) {
						writer.write("* $file got renamed and intersects with the current build list.\n")
						String msg = "*!! $file is renamed on branch $gitReference and intersects with the current build list."
						println msg
						
						// update build result
						if (props.reportConcurrentChangesIntersectionFailsBuild && props.reportConcurrentChangesIntersectionFailsBuild.toBoolean()) {
							props.error = "true"
							buildUtils.updateBuildResult(errorMsg:msg)
						} else {
							buildUtils.updateBuildResult(warningMsg:msg)
						}
					}
					else
						writer.write("  $file\n")
				}
			}

			if (concurrentDeletedFiles.size() != 0) {
				writer.write("** Deleted Files \n")
				concurrentDeletedFiles.each { file ->
					if (props.verbose) println " Deleted: ${file}"
					if (buildList.contains(file)) {
						writer.write("* $file is deleted and intersects with the current build list.\n")
						String msg = "*!! $file is deleted on branch $gitReference and intersects with the current build list."
						println msg
						
						// update build result
						if (props.reportConcurrentChangesIntersectionFailsBuild && props.reportConcurrentChangesIntersectionFailsBuild.toBoolean()) {
							props.error = "true"
							buildUtils.updateBuildResult(errorMsg:msg)
						} else {
							buildUtils.updateBuildResult(warningMsg:msg)
						}
					}
					else
						writer.write("  $file\n")
				}
			}
		}
	}
}

// Internal matcher methods

/**
 * create List of Regex Patterns
 */

def createMatcherPatterns(String property) {
	List<Pattern> patterns = new ArrayList<Pattern>()
	if (property) {
		property.split(',').each{ patternString ->
			Pattern pattern = Pattern.compile(patternString);
			patterns.add(pattern)
		}
	}
	return patterns
}

/**
* match a String against a list of patterns
*/
def matchesPattern(String name, List<Pattern> patterns) {
   def result = patterns.any { pattern ->
	   if (pattern.matcher(name).matches())
	   {
		   return true
	   }
   }
   return result
}
