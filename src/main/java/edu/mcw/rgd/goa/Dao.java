package edu.mcw.rgd.goa;

import edu.mcw.rgd.dao.impl.*;
import edu.mcw.rgd.dao.spring.IntStringMapQuery;
import edu.mcw.rgd.datamodel.Gene;
import edu.mcw.rgd.datamodel.SpeciesType;
import edu.mcw.rgd.datamodel.ontology.Annotation;
import edu.mcw.rgd.datamodel.ontologyx.Term;
import edu.mcw.rgd.process.Utils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.*;

/**
 * @author mtutaj
 * @since 2/13/12
 * all DAO code interacting with database
 */
public class Dao {

    private final Logger log = LogManager.getLogger("GoaSummary");

    private AnnotationDAO annotDAO = new AnnotationDAO();
    private OntologyXDAO ontDAO = new OntologyXDAO();
    private ReferenceDAO referenceDAO = new ReferenceDAO();
    private XdbIdDAO xdbIdDAO = new XdbIdDAO();

    private int createdBy;
    private int lastModifiedBy;
    private String staleAnnotDeleteThreshold;

    public String getConnectionInfo() {
        return ontDAO.getConnectionInfo();
    }

    /**
     * return a term by term accession id
     * @param termAcc term accession id
     * @return a term, or null if term accession id is invalid
     * @throws Exception
     */
    synchronized public Term getTermByAccId(String termAcc) throws Exception {

        Term term = _cacheTerms.get(termAcc);
        if( term==null ) {
            term = ontDAO.getTermByAccId(termAcc);
            if( term!=null ) {
                _cacheTerms.put(termAcc, term);
            }
        }

        return term;
    }
    private Map<String,Term> _cacheTerms = new HashMap<>();

    /** given term accession return a term object
     * @param termAcc term accession id
     * @return FullAnnot object or null if term accession id is invalid
     * @throws Exception when unexpected error in spring framework occurs
     */
    public Annotation queryTermInfoByTermAcc(String termAcc) throws Exception {

        Term term = getTermByAccId(termAcc);
        if( term==null )
            return null;

        Annotation fa = new Annotation();
        fa.setTermAcc(term.getAccId());
        fa.setTerm(term.getTerm());
        return fa;
    }


    /**
     * return gene info given accession id
     * @param accid accession id
     * @param xdbkey xdb key
     * @return List of RgdIds objects; if no match, empty list is returned; note: result is never null
     * @throws Exception
     */
    public List<Gene> queryGeneRgdInfoByAccid(String accid, int xdbkey) throws Exception {

        accid = accid.trim();

        String key = xdbkey+"+"+accid;
        List<Gene> list = _cacheGE.get(key);
        if( list==null ) {
            list = xdbIdDAO.getActiveGenesByXdbId(xdbkey, accid);

            // remove non-rat objects
            list.removeIf(ge -> ge.getSpeciesTypeKey() != SpeciesType.RAT);

            _cacheGE.put(key, list);
        }
        return list;
    }
    private Map<String,List<Gene>> _cacheGE = new HashMap<>();

    /**
     * get all annotations created by the pipeline in FULL_ANNOT table (for CREATED_BY=69)
     *
     * @return list of annotations
     * @throws Exception on spring framework dao failure
     */
    public List<Annotation> getAllAnnotations() throws Exception{

        final Date dtTomorrow = Utils.addDaysToDate(new Date(), 1);
        return annotDAO.getAnnotationsModifiedBeforeTimestamp(getCreatedBy(), dtTomorrow);
    }

    public int getCountOfAnnotations() throws Exception {
        return annotDAO.getCountOfAnnotationsForCreatedBy(createdBy);
    }

    // remove records from full_annot, but if the net annot drop could be greater than 5% threshold,
    // then abort the deletions and report it
    public int deleteFullAnnot(long startTime, Logger log, int initialAnnotCount) throws Exception {

        // extract delete threshold in percent
        int percentPos = getStaleAnnotDeleteThreshold().indexOf('%');
        int deleteThreshold = Integer.parseInt(getStaleAnnotDeleteThreshold().substring(0, percentPos));

        // set cutoff date to be one hour before the pipeline start
        // (to handle the case when app server clock time differs from database server clock time)
        Date cutoffDate = Utils.addHoursToDate(new Date(startTime), -1);

        int currentAnnotCount = getCountOfAnnotations();
        List<Annotation> annotsForDelete = annotDAO.getAnnotationsModifiedBeforeTimestamp(createdBy, cutoffDate);
        Logger logDelete = LogManager.getLogger("deleted");
        for( Annotation a: annotsForDelete ) {
            logDelete.debug(a.dump("|"));
        }
        int annotsForDeleteCount = annotsForDelete.size();
        int annotsForDeleteThreshold = (deleteThreshold * currentAnnotCount) / 100; // 5% delete threshold

        int newAnnotCount = currentAnnotCount - annotsForDeleteCount;
        if( initialAnnotCount - newAnnotCount > annotsForDeleteThreshold ) {

            log.warn(" STALE ANNOTATIONS DELETE THRESHOLD ("+getStaleAnnotDeleteThreshold()+") -- "+annotsForDeleteThreshold);
            log.warn(" STALE ANNOTATIONS TAGGED FOR DELETE     -- "+annotsForDeleteCount);
            log.warn(" STALE ANNOTATIONS DELETE THRESHOLD ("+getStaleAnnotDeleteThreshold()+") EXCEEDED -- no annotations deleted");
            return 0;
        }

        // delete stale annotations
        int rowsDeleted = annotDAO.deleteAnnotations(getCreatedBy(), cutoffDate);
        log.debug("Rows deleted from full_annot table: "+ rowsDeleted);
        return rowsDeleted;
    }

    /**
     * insert annotation into full_annot table
     * @param fa Annotation object
     * @throws Exception on spring framework dao failure
     */
    public boolean insertFullAnnot(Annotation fa) throws Exception {

        fa.setRefRgdId(fa.getRefRgdId()==null ? null : fa.getRefRgdId() > 0 ? fa.getRefRgdId() : null);
        fa.setCreatedBy(createdBy);
        fa.setLastModifiedBy(lastModifiedBy);

        try {
            annotDAO.insertAnnotation(fa);
            return true;
        } catch(java.sql.SQLIntegrityConstraintViolationException e) {
            return false;
        }
    }

    public void updateFullAnnot(Annotation fa) throws Exception {

        fa.setRefRgdId(fa.getRefRgdId()==null ? null : fa.getRefRgdId() > 0 ? fa.getRefRgdId() : null);
        fa.setLastModifiedBy(lastModifiedBy);
        fa.setLastModifiedDate(new Date());

        try {
            annotDAO.updateAnnotation(fa);
        } catch(org.springframework.dao.DuplicateKeyException e) {

            Annotation aInRgd = annotDAO.getAnnotation(fa.getKey());
            log.debug("========\nDUPLICATE KEY EXCEPTION in updateFullAnnot\n"+
                    "ANNOT_IN_RGD: "+aInRgd.dump("|")+"\n"+
                    "ANNOT_INCOMING: "+fa.dump("|"));
            throw e;
        }
    }

    public void init() {
        // update LAST_MODIFIED_DATE in batches of up to 1000 rows
        _updateLastModified = new ArrayList<>(1000);
    }

    public void finalizeUpdates() throws Exception {
        updateLastModified(_updateLastModified);
    }

    public void updateLastModified(int fullAnnotKey) throws Exception{
        _updateLastModified.add(fullAnnotKey);

        if( _updateLastModified.size()>=1000 ) {
            updateLastModified(_updateLastModified);
        }
    }

    private void updateLastModified(List<Integer> fullAnnotKeys) throws Exception {
        int updated = annotDAO.updateLastModified(fullAnnotKeys);
        log.debug("updated last modified date for "+updated+" rows");
        fullAnnotKeys.clear();
    }
    private List<Integer> _updateLastModified = null;


    /**
     * Check if a term can be used for curation.
     * @param termAcc term ACC id
     * @return true if the term doesn't have a "Not4Curation" synonym
     * @throws Exception if something wrong happens in spring framework
     */
    synchronized public boolean isForCuration(String termAcc) throws Exception {
        if( _not4CurationTermAccs==null ) {
            Set<String> result = new HashSet<>();
            result.addAll(ontDAO.getNot4CurationTermAccs("BP"));
            result.addAll(ontDAO.getNot4CurationTermAccs("CC"));
            result.addAll(ontDAO.getNot4CurationTermAccs("MF"));

            _not4CurationTermAccs = result;
        }
        return !_not4CurationTermAccs.contains(termAcc);
    }
    private Set<String> _not4CurationTermAccs;


    /**
     * check if a given term is a catalytic activity term GO:0003824, or a child of catalytic activity term
     * @param termAcc accession id of the term in question
     * @return true if the term is related to catalytic activity
     * @throws Exception if something wrong happens in spring framework
     */
    public boolean isCatalyticActivityTerm(String termAcc) throws Exception {
        final String catalyticActivityAccId = "GO:0003824";
        return termAcc.equals(catalyticActivityAccId) || ontDAO.isDescendantOf(termAcc, catalyticActivityAccId);
    }

    /**
     * get approved ontology qualifiers, like 'NOT' or 'contributes_to'
     * @return list of approved ontology qualifiers
     * @throws Exception if something wrong happens in spring framework
     */
    public Set<String> getOntologyQualifiers() throws Exception {
        if( _ontologyQualifiers==null ) {
            _ontologyQualifiers = new HashSet<>(ontDAO.getOntologyQualifiers());
        }
        return _ontologyQualifiers;
    }
    private Set<String> _ontologyQualifiers = null;

    public List<IntStringMapQuery.MapPair> getPubmedIdsAndRefRgdIds() throws  Exception {
        return referenceDAO.getPubmedIdsAndRefRgdIds();
    }

    public int getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(int createdBy) {
        this.createdBy = createdBy;
    }

    public int getLastModifiedBy() {
        return lastModifiedBy;
    }

    public void setLastModifiedBy(int lastModifiedBy) {
        this.lastModifiedBy = lastModifiedBy;
    }

    public void setStaleAnnotDeleteThreshold(String staleAnnotDeleteThreshold) {
        this.staleAnnotDeleteThreshold = staleAnnotDeleteThreshold;
    }

    public String getStaleAnnotDeleteThreshold() {
        return staleAnnotDeleteThreshold;
    }

}
