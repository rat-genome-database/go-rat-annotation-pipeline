package edu.mcw.rgd.goa;

import edu.mcw.rgd.dao.spring.IntStringMapQuery;
import edu.mcw.rgd.process.FileDownloader;
import edu.mcw.rgd.process.Utils;
import org.apache.log4j.Logger;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.net.InetAddress;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class PubMedManager {

    protected final Logger log = Logger.getLogger("GoaSummary");
    protected final Logger logImportedReferences = Logger.getLogger("importedReferences");

    private String importPubMedUrl;
    private Map<String,String> importPubmedToolHost;



    public int loadPmidAndRefRgdIdMap(Dao dao) throws Exception {
        _pmidRefRgdIdMap = new HashMap<>();

        for( IntStringMapQuery.MapPair p: dao.getPubmedIdsAndRefRgdIds() ) {
            _pmidRefRgdIdMap.put(p.stringValue, p.keyValue);
        }
        return _pmidRefRgdIdMap.size();
    }

    public Integer getRefIdByPubMed(String pubmedId) {
        return _pmidRefRgdIdMap.get(pubmedId);
    }
    Map<String,Integer> _pmidRefRgdIdMap;


    public void importMissingPubmedEntries(Dao dao, List<RatGeneAssoc> assocList) throws Exception {

        // populate PMID to ref_rgd_id map
        loadPmidAndRefRgdIdMap(dao);

        for( int retry=0; retry<100; retry++ ) {

            try {
                importMissingPubmedEntriesImpl(dao, assocList);
                break;
            } catch( Exception e) {
                log.warn("EXCEPTION encountered when importing PubMed ids");
                Utils.printStackTrace(e, log);
            }

            Thread.sleep(10000*(1+retry));
        }

        log.info("import of missing PubMed entries complete!");
    }

    void importMissingPubmedEntriesImpl(Dao dao, List<RatGeneAssoc> assocList) throws Exception {

        Map<String, String> pubmedIdsToImport = new ConcurrentHashMap<>();

        //check for DB_REFERENCE only if DB_reference starts with PMID:XXX"
        Collections.shuffle(assocList);
        assocList.parallelStream().forEach( rec -> {
            String dbRef = rec.getDbReferences();

            if (dbRef.startsWith("PMID:")) {
                dbRef = dbRef.replace("PMID:", "");
                Integer refRgdId = getRefIdByPubMed(dbRef);
                if( refRgdId==null ) {
                    pubmedIdsToImport.put(dbRef, dbRef);
                }
            }
        });

        int importedPubMedIds = importPubMedEntries(pubmedIdsToImport.keySet());
        if( importedPubMedIds!=0 ) {
            // repopulate PMID to ref_rgd_id map
            loadPmidAndRefRgdIdMap(dao);
        }
    }

    int importPubMedEntries(Set<String> pubMedIdsToImport) throws Exception {

        log.info(" PubMed ids not in RGD: " + pubMedIdsToImport.size());
        log.info(" Import URL: "+getImportPubMedUrl());

        InetAddress inetAddress = InetAddress. getLocalHost();
        String hostName = inetAddress. getHostName().toLowerCase();
        String pubmedToolHostUrl = getImportPubmedToolHost().get(hostName);
        if( pubmedToolHostUrl==null ) {
            throw new Exception("Update properties file and provide mapping for host "+hostName);
        }

        int importedPubMedIds = 0;
        for( String pubMedId: pubMedIdsToImport ) {
            String extFile = pubmedToolHostUrl+getImportPubMedUrl()+pubMedId;

            FileDownloader downloader = new FileDownloader();
            downloader.setExternalFile(extFile);
            downloader.setLocalFile("data/pmid_"+pubMedId+".html");
            downloader.setPrependDateStamp(true);
            String localFile = downloader.download();

            if( parseRefImportReport(localFile, pubMedId) ) {
                importedPubMedIds++;
            }
        }

        log.info(" References for PubMed ids imported: "+importedPubMedIds);
        return importedPubMedIds;
    }

    /**
     *
     * @param localFile local file name
     * @param pubMedId PubMed id
     * @return true if the reference with given PubMedId has been imported into RGD successfully
     * @throws IOException
     */
    boolean parseRefImportReport(String localFile, String pubMedId) throws IOException {

        // load entire report file into a string
        String localFileContent = "";
        String buf;
        BufferedReader reader = new BufferedReader(new FileReader(localFile));
        while( (buf=reader.readLine())!=null ) {
            localFileContent += buf +"\n";
        }
        reader.close();

        // if successful, the report will contain the RGD_ID of the imported reference
        // f.e. "/rgdCuration/?module=curation&func=addReferenceToBucket&RGD_ID=7395592"
        int pos1 = localFileContent.indexOf("RGD_ID=");
        int pos2 = localFileContent.indexOf("\"", pos1);
        if( pos1>0 && pos1<pos2 ) {
            logImportedReferences.info("   PMID:"+pubMedId+" ==> REF_"+localFileContent.substring(pos1, pos2));
            return true;
        }

        // if there is an error
        pos1 = localFileContent.indexOf("<title>");
        pos2 = localFileContent.indexOf("</title>");
        if( pos1>0 && pos1<pos2 ) {
            log.info("   PMID:"+pubMedId+" - "+localFileContent.substring(pos1+7, pos2));
        }
        else {
            log.info("   PMID:"+pubMedId+" - "+localFileContent);
        }
        return false;
    }


    public void setImportPubMedUrl(String importPubMedUrl) {
        this.importPubMedUrl = importPubMedUrl;
    }

    public String getImportPubMedUrl() {
        return importPubMedUrl;
    }

    public void setImportPubmedToolHost(Map<String,String> importPubmedToolHost) {
        this.importPubmedToolHost = importPubmedToolHost;
    }

    public Map<String,String> getImportPubmedToolHost() {
        return importPubmedToolHost;
    }
}
