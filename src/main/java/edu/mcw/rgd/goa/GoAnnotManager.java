package edu.mcw.rgd.goa;

import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.io.*;

import edu.mcw.rgd.datamodel.Gene;
import edu.mcw.rgd.datamodel.RgdId;
import edu.mcw.rgd.datamodel.ontology.Annotation;
import edu.mcw.rgd.process.CounterPool;
import edu.mcw.rgd.process.FileDownloader;
import edu.mcw.rgd.process.Utils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.xml.XmlBeanDefinitionReader;
import org.springframework.core.io.FileSystemResource;

import edu.mcw.rgd.log.RGDSpringLogger;


/**
 * Processes files in gaf2.2 format
 * <p>
 * In the last stage of processing the pipeline performs update on annotations for Olr gene.
 * <p>
 * Uninformative high-level terms are skipped from loading (GOC rule)
 * <p>
 * GO_AR:0000007 IPI should not be used with catalytic activity molecular function terms: implemented
 */
public class GoAnnotManager {
	
	DataValidationImpl dataValidation;
	Dao dao;
	RGDSpringLogger rgdLogger = new RGDSpringLogger();
    String goRelLocalFile;

    int specialRefRgdId;
    CounterPool counters;

	static long startMilisec=System.currentTimeMillis();
    static DateFormat dateFormat = new SimpleDateFormat("yyyyMMdd");

	final Logger log = LogManager.getLogger("GoaSummary");
	final Logger logUnMatchedDbObjID = LogManager.getLogger("unMatchedDbObjID");
	final Logger logUnMatchedGOID = LogManager.getLogger("unMatchedGOID");
	final Logger logDuplAnnot = LogManager.getLogger("duplAnnot"); // incoming annot matching annot in RGD
	final Logger logUnMatchedPubmed = LogManager.getLogger("unMatchedPubmed");
	final Logger logLoaded = LogManager.getLogger("loaded");
	final Logger logRejected = LogManager.getLogger("rejected");
    final Logger logHighLevelGoTerm = LogManager.getLogger("highLevelGoTerm");
    final Logger logCatalyticActivityIPIGoTerm = LogManager.getLogger("catalyticActivityIPIGoTerm");

    private String localFile;
    private String nonRgdFile;
    private String goaFile;
    private String goaRgdTxt;
    private String version;
    private List<String> goaRatFiles;
    private String goRelFile;
    private Set<Integer> refRgdIdsForGoPipelines;
    private Map<String,String> sourceSubst;
    private String threeMonthOldDate;
    private PubMedManager pubMedManager;

    public static void main(String[] args) throws Exception {

        DefaultListableBeanFactory bf = new DefaultListableBeanFactory();
        new XmlBeanDefinitionReader(bf).loadBeanDefinitions(new FileSystemResource("properties/AppConfigure.xml"));
        GoAnnotManager goAnnotManager=(GoAnnotManager) (bf.getBean("goAnnotManager"));

        try {
            goAnnotManager.startGoaPipeline();
        } catch( Exception e ) {
            Utils.printStackTrace(e, goAnnotManager.getLogger());
            goAnnotManager.getLogger().info( goAnnotManager.counters.dumpAlphabetically() );
        }
	}

	public void startGoaPipeline() throws Exception {

        getLogger().info("----- "+getVersion());

        Date dateStart = new Date();
        SimpleDateFormat sdt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        log.info("  started at: "+sdt.format(dateStart));
        log.info("  "+getDao().getConnectionInfo());
        log.info("===");

        dataValidation.setDao(getDao());

        int initialAnnotCount = dataValidation.loadPipelineAnnotations();
        log.info("Initial annotation count in RGD: "+initialAnnotCount);

        downloadDataFiles();

		run();

        wrapUp();

        cleanFullAnnot(initialAnnotCount);

        int linenum = counters.get("linenum");
        int totDups = counters.get("totDups");
        int totInserted = counters.get("totInserted");
        int updatedAnnots = counters.get("updatedAnnots");
        int totTopLevelTermsSkipped = counters.get("totTopLevelTermsSkipped");
        int totCatalyticActivityIPITermsSkipped = counters.get("totCatalyticActivityIPITermsSkipped");
        int skippedAnnots = counters.get("skippedAnnots");
        int totUnPubmed = counters.get("totUnPubmed");
        int totUnObjid = counters.get("totUnObjid");
        int totUnGoid = counters.get("totUnGoid");
        int IEA_GO_annotsWithUpdatedCreatedDate = counters.get("IEA_GO_annotsWithUpdatedCreatedDate");
        int IEA_GO_annotsWithUntouchedCreatedDate = counters.get("IEA_GO_annotsWithUntouchedCreatedDate");
        int dataSourceSubstitutions = counters.get("dataSourceSubstitutions");
        int rowDeleted = counters.get("rowDeleted");
        int qcRestarts = counters.get("qcRestarts");
        int createdDateUpdated = counters.get("createdDateUpdated");

		//generate summary for sending email.
		long endMilisec=System.currentTimeMillis();
	    log.info("Annotations in incoming GOA file: "+linenum);
        log.info("Annotations matching RGD: "+totDups);
		log.info("Annotations loaded into RGD: "+totInserted);
        if( updatedAnnots!=0 ) {
            log.info("Annotations updated in RGD: "+updatedAnnots);
        }
        if( createdDateUpdated!=0 ) {
            log.info("  -- including annotations with ORIGINAL_CREATED_DATE updated: "+createdDateUpdated);
        }
        log.info("Annotations in RGD final count: "+getDao().getCountOfAnnotations());
        if( totTopLevelTermsSkipped>0 )
            log.info("Annotations skipped (uninformative top-level term): "+totTopLevelTermsSkipped);
        if( totCatalyticActivityIPITermsSkipped>0 )
            log.info("Annotations skipped (catalytic activity IPI terms): "+totCatalyticActivityIPITermsSkipped);
        if( skippedAnnots>0 )
            log.info("Annotations skipped (matching annotations from other pipelines): "+skippedAnnots);
        if( qcRestarts>0 )
            log.info("WARNING: There were QC restarts: "+qcRestarts);
        log.info("Annotations with removed obsolete relations: "+dataValidation.getAnnotationsWithRemovedObsoleteRelations());
        log.info("  total count of removed obsolete relations: "+dataValidation.getCountOfRemovedObsoleteRelations());
		log.info("-------------------------------------------------------");
		log.info("Log file for annotations that use a PubMed ID which is not in RGD. These annotations are loaded.");
		log.info("*** Unmatched PubMed ID: "+totUnPubmed);
		log.info("Log files of annotations that do not get loaded.");
		log.info("*** Unmatched Object ID: "+totUnObjid);
		log.info("*** Unmatched GO ID: "+totUnGoid);
        log.info("*** IEA GO annots with updated created date: "+IEA_GO_annotsWithUpdatedCreatedDate);
        log.info("*** IEA GO annots with untouched created date: "+IEA_GO_annotsWithUntouchedCreatedDate);
        log.info("*** DATA_SRC substitutions: "+dataSourceSubstitutions);
		log.info("-----------------------------------------");
		log.info("Processing time elapsed: "+ Utils.formatElapsedTime(startMilisec, endMilisec));
		
		//store info in Database log table report_extracts
		rgdLogger.log("GOAnnoationsRat","GOAAnnotIncoming",linenum);
		rgdLogger.log("GOAnnoationsRat","GOAAnnotLoaded",totInserted);
		rgdLogger.log("GOAnnoationsRat","GOAAnnotUnmatchedPubMed",totUnPubmed);
		rgdLogger.log("GOAnnoationsRat","GOAAnnotDups",totDups);
		rgdLogger.log("GOAnnoationsRat","GOAAnnotUnmatchedObj",totUnObjid);
		rgdLogger.log("GOAnnoationsRat","GOAAnnotUnmatchedGO",totUnGoid);
		rgdLogger.log("GOAnnoationsRat","timeToRun",(endMilisec-startMilisec)/1000);
		rgdLogger.log("GOAnnoationsRat","totalAnnotRemoved",rowDeleted);
	}

	public void cleanFullAnnot(int initialAnnotCount) throws Exception {
		int rowDeleted = dao.deleteFullAnnot(startMilisec, log, initialAnnotCount);
		log.info("Stale annotations removed from FULL_ANNOT table: "+rowDeleted);
		counters.add("rowDeleted", rowDeleted);
	}


    /**
     * read input file, parse it, convert every line in RatGeneAssoc records, and run every record through qualityCheck
     * @throws Exception
     */
	public void run() throws Exception {

        // precompute a 3-month-old date to be used for some olfactory annotations
        Calendar calendar = Calendar.getInstance();
        calendar.setTime(new Date());
        calendar.add(Calendar.DATE, -90);
        calendar.set(Calendar.DAY_OF_MONTH, 1); // always set to the first day of the month
        threeMonthOldDate = formatDate(calendar.getTime());

        processGoRelObo();

        dao.init();

		log.debug("start parsing the input file");

        // parse file ("data/goa_rat")
        List<RatGeneAssoc> incomingRecords = loadIncomingRecords();

        getPubMedManager().importMissingPubmedEntries(dao, incomingRecords);

        // do quality check for each row of data
        if( !qcAll(incomingRecords) ) {
            throw new Exception("LOGIC FAILURE! Aborting pipeline!");
        }

        dao.finalizeUpdates();

        int invalidLines = counters.get("invalidLines");
        if( invalidLines>0 ) {
            log.warn("WARNING: file format problem: there are "+invalidLines+" lines having column count different than 17");
        }
	}

	List<RatGeneAssoc> loadIncomingRecords() throws Exception {

        List<RatGeneAssoc> incomingRecords = new ArrayList<>();

        try( BufferedReader br = new BufferedReader(new FileReader(getGoaFile())) ) {

            String strLine;
            while ((strLine = br.readLine()) != null) {

                if (strLine.startsWith("!")) {
                    continue;
                }

                String line = strLine.replace("MGI:MGI:", "MGI:");

                String[] tokens = line.split("[\\t]", -1);
                if (tokens.length != 17) {
                    counters.increment("invalidLines");
                    continue;
                }

                RatGeneAssoc ratGeneAssoc = new RatGeneAssoc();
                ratGeneAssoc.setDb(tokens[0]);
                ratGeneAssoc.setDbObjectId(tokens[1]);
                ratGeneAssoc.setDbObjectSymbol(tokens[2]);
                ratGeneAssoc.setQualifier(qcQualifier(tokens[3]));
                ratGeneAssoc.setGoId(tokens[4]);
                ratGeneAssoc.setDbReferences(tokens[5]);
                ratGeneAssoc.setEvidence(tokens[6]);
                ratGeneAssoc.setWith(tokens[7]);
                ratGeneAssoc.setAspect(tokens[8]);
                ratGeneAssoc.setDbObjectName(tokens[9]);
                ratGeneAssoc.setSynonym(tokens[10]);
                ratGeneAssoc.setDbObjectType(tokens[11]);
                ratGeneAssoc.setTaxonId(tokens[12]);
                ratGeneAssoc.setCreatedDate(tokens[13]);

                // set data source, and perform any necessary substitutions (f.e. 'UniProt' -> 'UniProtKB')
                if (ratGeneAssoc.setAssignedBy(tokens[14], getSourceSubst())) {
                    counters.increment("dataSourceSubstitutions");
                }

                ratGeneAssoc.setAnnotationExtension(tokens[15]);
                ratGeneAssoc.setGeneProductFormId(tokens[16]);

                // do quality check for each row of data
                incomingRecords.add(ratGeneAssoc);
            }
        }

        return incomingRecords;
    }

    boolean qcAll(List<RatGeneAssoc> incomingRecords) throws Exception {

	    List<RatGeneAssoc> recordsToProcess = new ArrayList<>(incomingRecords);

	    final int MAX_RESTARTS = 100;
	    int restart=0;
	    for( ; restart<MAX_RESTARTS; restart++ ) {

	        log.info("    starting qc for "+recordsToProcess.size()+" records, iteration "+restart);

            counters = new CounterPool();

            boolean doRestart = false;
            Collections.shuffle(recordsToProcess);

            Iterator<RatGeneAssoc> it = recordsToProcess.iterator();

            while( it.hasNext() ) {
                RatGeneAssoc rec = it.next();

                try {
                    qualityCheck(rec);
                    counters.increment("linenum");
                    it.remove();
                } catch(org.springframework.dao.DuplicateKeyException e) {
                    doRestart = true;
                    break;
                }
            }
            if( doRestart ) {
                continue;
            }

            // all records processed without problems -- break the restart loop
            break;
        }
        if( restart!=0 ) {
            counters.add("qcRestarts", restart);
            if( restart>=MAX_RESTARTS ) {
                log.warn("TOO MANY QC RESTARTS! Rerun the pipeline!");
                return false;
            }
        }
        log.info("    finished qc, iterations "+restart);
        return true;
    }

    String qcQualifier(String qualifier) throws Exception {
        if( qualifier!=null && !qualifier.isEmpty() ) {
            // multiple qualifiers are possible, f.e.: 'NOT|contributes_to'
            for( String q: qualifier.split("[\\|]") ) {
                if( !dao.getOntologyQualifiers().contains(q) ) {
                    //throw new Exception("Unrecognized qualifier: "+q);
                    log.warn("Unrecognized qualifier: "+q);
                }
            }
        }
        return qualifier;
    }


	public boolean qualityCheck(RatGeneAssoc rec) throws Exception {

		// filter-out high-level GO terms
        if( !dao.isForCuration(rec.getGoId()) ) {
            counters.increment("totTopLevelTermsSkipped");
            writeLogfile(logHighLevelGoTerm, rec);
            return true;
        }

        if( rec.getEvidence().equals("IPI") && dao.isCatalyticActivityTerm(rec.getGoId()) ) {
            counters.increment("totCatalyticActivityIPITermsSkipped");
            writeLogfile(logCatalyticActivityIPIGoTerm, rec);
            return true;
        }

        dataValidation.validateExtensionRelations(rec);

		//make sure the Go term exists in RGD.ont_terms table
		//query term from ont_terms table by term_acc($GO_ID)
        Annotation fullAnnot=dao.queryTermInfoByTermAcc(rec.getGoId());
		
		if(fullAnnot!=null) {
			// multiple values may returned
            log.debug("DbObjectId "+rec.getDb()+" - "+rec.getDbObjectId());
			List<Gene> geneInfo=dataValidation.checkDbObjectId(rec);

			//Does $DB_Object_ID = any Swiss-Prot/UniProt/GenBank protein ID in RGD (RGD_ACC_XDB table)?
			if(geneInfo.size()> 0) {
				 log.debug("One or more Gene(s) found for the given DB_OBJECT_ID");
				 if( !qcChecksForMatchedDbObjectId(geneInfo, fullAnnot, rec) )
                     return false;
			}
			else {
				// check rat_xrefs file
				// will develop in phase II
				// put in log file for now
				counters.increment("totUnObjid");
				writeLogfile(logUnMatchedDbObjID, rec);
				logRejected.debug("Log File\t<NO_DB_OBJECT_ID found in RGD>\tDB_OBJECT_ID="+rec.getDbObjectId());
			}
		}
		else {
			counters.increment("totUnGoid");
			writeLogfile(logUnMatchedGOID, rec);
			logRejected.debug("Log File\n<NO_GO_ID found in RGD>\tGo_ID="+rec.getGoId());
		}
        return true;
	}
	
	public boolean qcChecksForMatchedDbObjectId(List<Gene> geneInfos, Annotation fullAnnot, RatGeneAssoc ratGeneAssoc) throws Exception {
	
        setFullAnnotBean(fullAnnot, ratGeneAssoc);

        // COMPUTE XREF_SOURCE AND REF_RGD_ID
        //
		//logger.info("more QC checks for each matched Gene");
		//check for DB_REFERENCE only if DB_reference start with "PMID:XXX"
		String dbRef = ratGeneAssoc.getDbReferences();
		if(dbRef.startsWith("PMID:")){
			dbRef=dbRef.replace("PMID:","");
			fullAnnot.setRefRgdId(getPubMedManager().getRefIdByPubMed(dbRef));

            if (fullAnnot.getRefRgdId()==null || fullAnnot.getRefRgdId() == 0) {
                fullAnnot.setXrefSource(ratGeneAssoc.getDbReferences());
            }
		}
		else {
			// any other format other than "PMID:xxx"
			// no need to check uniqueness
			// create an entry in references table(a special ref_rgd_id will be created)
			// load the record in full_annot table
			// store $Db_Reference in new field xref_source field in full_annot table
			fullAnnot.setRefRgdId(specialRefRgdId);
			fullAnnot.setXrefSource(ratGeneAssoc.getDbReferences());
        }
        ratGeneAssoc.setRefRgdId(Utils.NVL(fullAnnot.getRefRgdId(),0));

        for( Gene geneInfo: geneInfos ){
            setFullAnnotBean(fullAnnot, geneInfo);
            fullAnnot.setAnnotatedObjectRgdId(geneInfo.getRgdId());

            int code; // 0 - up-to-date; 1 - skipped; 2 - inserted; 3 - updated
            code = dataValidation.upsert(fullAnnot, counters);

            if( code==2 ) { // inserted
                counters.increment("totInserted");
                logLoaded.debug("insert successful\tRGD_ID="+geneInfo.getRgdId()+"\tGO_ID="+ratGeneAssoc.getGoId()+"\tPubmed="+dbRef+"\tref_rgd_id="+fullAnnot.getRefRgdId());
            }
            else if( code==3 ) { // updated
                counters.increment("updatedAnnots");
            } else if( code==0 ){ // up-to-date
                counters.increment("totDups");

                writeLogfile(logDuplAnnot, ratGeneAssoc);
                dao.updateLastModified(fullAnnot.getKey());
            } else if( code==1 ){
                counters.increment("skippedAnnots");
                return true;
            } else {
                throw new Exception("unexpected code="+code);
            }

            if( fullAnnot.getRefRgdId()!=null && fullAnnot.getRefRgdId()==0 ) {
                counters.increment("totUnPubmed");
                writeLogfile(logUnMatchedPubmed, ratGeneAssoc);
                logRejected.debug("Log File\t<No matching pubmed_id found in RGD>\t GO_ID="+ratGeneAssoc.getGoId()+"\tDB_REFERENCE="+ratGeneAssoc.getDbReferences());
            }
        }

        return true;
	}

	public void writeLogfile(Logger theLog, RatGeneAssoc ratGeneAssoc) {

        // export data in gaf 2.2 format
        //
        // fixup: when exporting IEA annotations for GO and olfactory genes pipelines, set the created-date
        //        to be 3 months old
        String createdDate = ratGeneAssoc.getCreatedDate();
        if( ratGeneAssoc.getGoId().startsWith("GO:") && ratGeneAssoc.getEvidence().equals("IEA") ) {
            if( getRefRgdIdsForGoPipelines().contains(ratGeneAssoc.getRefRgdId()) ) {
                // this update is to prevent the GOC error that occurs when an IEA annotation is more than a year old
                createdDate = threeMonthOldDate;
                counters.increment("IEA_GO_annotsWithUpdatedCreatedDate");
            } else {
                counters.increment("IEA_GO_annotsWithUntouchedCreatedDate");
            }
        }


		theLog.debug("\t"+ratGeneAssoc.getDb()+"\t"+
					ratGeneAssoc.getDbObjectId()+"\t"+
					ratGeneAssoc.getDbObjectSymbol()+"\t"+
					ratGeneAssoc.getQualifier()+"\t"+
					ratGeneAssoc.getGoId()+"\t"+
					ratGeneAssoc.getDbReferences()+"\t"+
					ratGeneAssoc.getEvidence()+"\t"+		
					ratGeneAssoc.getWith()+"\t"+
					ratGeneAssoc.getAspect()+"\t"+
					ratGeneAssoc.getDbObjectName()+"\t"+
					ratGeneAssoc.getSynonym()+"\t"+
					ratGeneAssoc.getDbObjectType()+"\t"+
					ratGeneAssoc.getTaxonId()+"\t"+
					createdDate+"\t"+
					ratGeneAssoc.getAssignedBy()+"\t"+
                    ratGeneAssoc.getAnnotationExtension() + "\t"+
                    ratGeneAssoc.getGeneProductFormId()
        );
	}

    //  calls to sdt.format must be synchronized
    synchronized String formatDate(java.util.Date dt) {
        // date formatting is not synchronized :-(
        return dt != null ? dateFormat.format(dt) : "";
    }

    public void setFullAnnotBean(Annotation fullAnnot, Gene geneInfo) {

        fullAnnot.setAnnotatedObjectRgdId(geneInfo.getRgdId());
        fullAnnot.setObjectName(geneInfo.getName());
        fullAnnot.setObjectSymbol(geneInfo.getSymbol());
        fullAnnot.setRgdObjectKey(RgdId.OBJECT_KEY_GENES);
    }

	public void setFullAnnotBean(Annotation fullAnnot, RatGeneAssoc ratGeneAssoc) throws ParseException {

		fullAnnot.setTermAcc(ratGeneAssoc.getGoId());
		fullAnnot.setWithInfo(ratGeneAssoc.getWith());
		fullAnnot.setEvidence(ratGeneAssoc.getEvidence());
		fullAnnot.setAspect(ratGeneAssoc.getAspect());
		fullAnnot.setDataSrc(ratGeneAssoc.getAssignedBy());
		fullAnnot.setNotes(ratGeneAssoc.getDbReferences());
        fullAnnot.setQualifier(ratGeneAssoc.getQualifier());
        fullAnnot.setAnnotationExtension(ratGeneAssoc.getAnnotationExtension());
        fullAnnot.setGeneProductFormId(ratGeneAssoc.getGeneProductFormId());

        fullAnnot.setOriginalCreatedDate(dateFormat.parse(ratGeneAssoc.getCreatedDate()));
    }

    void downloadGoRelFile() throws Exception {
        FileDownloader downloader = new FileDownloader();
        downloader.setExternalFile(getGoRelFile());
        downloader.setLocalFile("data/gorel.obo");
        downloader.setUseCompression(true);
        downloader.setAppendDateStamp(true);

        goRelLocalFile = downloader.download();
        log.debug("downloaded "+downloader.getLocalFile());
    }

    // download all input files and merge them into one file
    void downloadInputFiles() throws Exception {

        BufferedWriter out = Utils.openWriter(getLocalFile());
        for( String goaRatFile: getGoaRatFiles() ) {
            FileDownloader downloader = new FileDownloader();
            downloader.setExternalFile(goaRatFile);
            downloader.setLocalFile("data/"+goaRatFile.substring(1+goaRatFile.lastIndexOf('/')));
            downloader.setUseCompression(false);
            downloader.setAppendDateStamp(true);

            String localFile = downloader.download();
            log.debug("downloaded "+downloader.getLocalFile());
            BufferedReader reader = Utils.openReader(localFile);
            String line;
            while( (line=reader.readLine())!=null ) {
                out.write(line);
                out.newLine();
            }
            reader.close();
        }
        out.close();
    }

    void downloadDataFiles() throws Exception {

        downloadGoRelFile();
        downloadInputFiles();

        BufferedReader reader = Utils.openReader(getLocalFile());

        // extract NON_RGD records
        // (column 14 must be different than RGD)
        BufferedWriter nonRgdFile = Utils.openWriter(this.getNonRgdFile());
        BufferedWriter goaFile = Utils.openWriter(this.getGoaFile());

        int linesInInputFile = 0;
        int linesInNonRgdFile = 0;
        int linesInGoaFile = 0;

        String line;
        while( (line=reader.readLine())!=null ) {
            linesInInputFile++;

            String[] cols = line.split("[\\t]", -1);
            if( cols.length<17 || !cols[14].equals("RGD") ) {

                linesInNonRgdFile++;

                nonRgdFile.write(line);
                nonRgdFile.newLine();

                // write lines with /UniProtKB/ /RefSeq/ /ENSEMBL/ to goa_rat file
                if( cols[0].equals("UniProtKB") || cols[0].equals("RefSeq") || cols[0].equals("ENSEMBL") ) {

                    linesInGoaFile++;

                    goaFile.write(line);
                    goaFile.newLine();
                }
            }
        }
        reader.close();
        nonRgdFile.close();
        goaFile.close();

        log.info("Line count in merged input file: "+linesInInputFile);
        log.info("Line count in goa_rat_nonrgd: "+linesInNonRgdFile);
        log.info("Line count in goa_rat file: " + linesInGoaFile);
    }

    void wrapUp() throws IOException {

        Set<String> uniqueLines = new TreeSet<>();
        appendFile("logs/duplAnnot.log", uniqueLines);
        appendFile("logs/unMatchedDbObjID.log", uniqueLines);

        BufferedWriter goaFile = Utils.openWriter(this.getGoaRgdTxt());
        for( String gafLine: uniqueLines ) {
            goaFile.write(gafLine);
        }
        goaFile.close();

        log.info("Line count in goa_rgd.txt: " + uniqueLines.size());
    }

    void appendFile(String inFile, Set<String> lineSet) throws IOException {
        BufferedReader reader = Utils.openReader(inFile);
        String line;

        while( (line=reader.readLine())!=null ) {

            int tabPos = line.indexOf('\t');
            if( tabPos >= 0 ) {
                String gafLine = line.substring(tabPos+1)+"\n";
                lineSet.add(gafLine);
            }
        }
        reader.close();
    }

    void processGoRelObo() throws IOException {

        Set<String> obsoleteRelations = new HashSet<>();

        BufferedReader reader = Utils.openReader(goRelLocalFile);
        String line;
        boolean isObsolete = false;
        String id = null;

        while( (line=reader.readLine())!=null ) {
            if( line.startsWith("[Typedef]") ) {
                // process entry
                if( id!=null && isObsolete ) {
                    obsoleteRelations.add(id);
                }
                // initialize new entry
                id = null;
                isObsolete = false;
            }
            else if( line.startsWith("id: ") ) {
                id = line.substring(4).trim();
            }
            else if( line.startsWith("is_obsolete: ") ) {
                isObsolete = line.contains("true");
            }
        }
        reader.close();

        // process last entry
        if( id!=null && isObsolete ) {
            obsoleteRelations.add(id);
        }

        this.log.info(" loaded obsolete relations from gorel.obo: "+obsoleteRelations.size());
        dataValidation.setObsoleteRelations(obsoleteRelations);
    }

	public Logger getLogger() {
        return this.log;
    }

	public DataValidationImpl getDataValidation() {
		return dataValidation;
	}
	
	public void setDataValidation(DataValidationImpl dataValidation) {
		this.dataValidation = dataValidation;
	}
	
	/**
	 * @return Returns the dao.
	 */
	public Dao getDao() {
		return dao;
	}
	/**
	 * @param dao The dao to set.
	 */
	public void setDao(Dao dao) {
		this.dao = dao;
	}
	
	/**
	 * @return Returns the specialRefRgdId.
	 */
	public int getSpecialRefRgdId() {
		return specialRefRgdId;
	}
	/**
	 * @param specialRefRgdId The specialRefRgdId to set.
	 */
	public void setSpecialRefRgdId(int specialRefRgdId) {
		this.specialRefRgdId = specialRefRgdId;
	}

    public void setLocalFile(String localFile) {
        this.localFile = localFile;
    }

    public String getLocalFile() {
        return localFile;
    }

    public void setNonRgdFile(String nonRgdFile) {
        this.nonRgdFile = nonRgdFile;
    }

    public String getNonRgdFile() {
        return nonRgdFile;
    }

    public void setGoaFile(String goaFile) {
        this.goaFile = goaFile;
    }

    public String getGoaFile() {
        return goaFile;
    }

    public void setGoaRgdTxt(String goaRgdTxt) {
        this.goaRgdTxt = goaRgdTxt;
    }

    public String getGoaRgdTxt() {
        return goaRgdTxt;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public String getVersion() {
        return version;
    }

    public void setGoaRatFiles(List<String> goaRatFiles) {
        this.goaRatFiles = goaRatFiles;
    }

    public List<String> getGoaRatFiles() {
        return goaRatFiles;
    }

    public void setGoRelFile(String goRelFile) {
        this.goRelFile = goRelFile;
    }

    public String getGoRelFile() {
        return goRelFile;
    }

    public void setRefRgdIdsForGoPipelines(Set<Integer> refRgdIdsForGoPipelines) {
        this.refRgdIdsForGoPipelines = refRgdIdsForGoPipelines;
    }

    public Set<Integer> getRefRgdIdsForGoPipelines() {
        return refRgdIdsForGoPipelines;
    }

    public void setSourceSubst(Map<String,String> sourceSubst) {
        this.sourceSubst = sourceSubst;
    }

    public Map<String,String> getSourceSubst() {
        return sourceSubst;
    }

    public PubMedManager getPubMedManager() {
        return pubMedManager;
    }

    public void setPubMedManager(PubMedManager pubMedManager) {
        this.pubMedManager = pubMedManager;
    }
}
