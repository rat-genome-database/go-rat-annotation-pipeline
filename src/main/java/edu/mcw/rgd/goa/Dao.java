package edu.mcw.rgd.goa;

import edu.mcw.rgd.dao.impl.AnnotationDAO;
import edu.mcw.rgd.dao.impl.GenomicElementDAO;
import edu.mcw.rgd.dao.impl.OntologyXDAO;
import edu.mcw.rgd.dao.impl.XdbIdDAO;
import edu.mcw.rgd.datamodel.GenomicElement;
import edu.mcw.rgd.datamodel.RgdId;
import edu.mcw.rgd.datamodel.SpeciesType;
import edu.mcw.rgd.datamodel.ontology.Annotation;
import edu.mcw.rgd.datamodel.ontologyx.Term;
import edu.mcw.rgd.process.Utils;
import org.apache.log4j.Logger;
import org.springframework.jdbc.object.MappingSqlQuery;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

/**
 * @author mtutaj
 * @since 2/13/12
 * all DAO code interacting with database
 */
public class Dao {

    private final Logger logger = Logger.getLogger(getClass());

    private AnnotationDAO annotDAO = new AnnotationDAO();
    private GenomicElementDAO genomicElementDAO = new GenomicElementDAO();
    private OntologyXDAO ontDAO = new OntologyXDAO();
    private XdbIdDAO xdbIdDAO = new XdbIdDAO();

    private int createdBy;
    private int lastModifiedBy;

    /**
     * return a term by term accession id
     * @param termAcc term accession id
     * @return a term, or null if term accession id is invalid
     * @throws Exception
     */
    public Term getTermByAccId(String termAcc) throws Exception {
        return ontDAO.getTermByAccId(termAcc);
    }

    /** given term accession return a term object
     * @param termAcc term accession id
     * @return FullAnnot object or null if term accession id is invalid
     * @throws Exception when unexpected error in spring framework occurs
     */
    public Annotation queryTermInfoByTermAcc(String termAcc) throws Exception {

        Term term = _cacheTerms.get(termAcc);
        if( term==null ) {
            term = getTermByAccId(termAcc);
            if( term!=null ) {
                _cacheTerms.put(termAcc, term);
            }
        }

        if( term==null )
            return null;

        Annotation fa = new Annotation();
        fa.setTermAcc(term.getAccId());
        fa.setTerm(term.getTerm());
        return fa;
    }

    private Map<String,Term> _cacheTerms = new HashMap<>();

    /**
     * return gene info given accession id
     * @param accid accession id
     * @param xdbkey xdb key
     * @return List of RgdIds objects; if no match, empty list is returned; note: result is never null
     * @throws Exception
     */
    public List<GenomicElement> queryGeneRgdInfoByAccid(String accid, int xdbkey) throws Exception {

        accid = accid.trim();

        String key = xdbkey+"+"+accid;
        List<GenomicElement> list = _cacheGE.get(key);
        if( list==null ) {
            list = genomicElementDAO.getElementsByAccId(accid, xdbkey, RgdId.OBJECT_KEY_GENES, SpeciesType.RAT);
            _cacheGE.put(key, list);
        }
        return list;
    }

    Map<String,List<GenomicElement>> _cacheGE = new HashMap<>();

    /**
     * get annotation notes given a list of values that comprise unique key:
     * TERM_ACC+ANNOTATED_OBJECT_RGD_ID+REF_RGD_ID+EVIDENCE+WITH_INFO+QUALIFIER+XREF_SOURCE
     * @param annot Annotation object with the following fields set: TERM_ACC+ANNOTATED_OBJECT_RGD_ID+REF_RGD_ID+EVIDENCE+WITH_INFO+QUALIFIER+XREF_SOURCE
     * @return Annotation object with annot_key and notes field set, or null if invalid key
     * @throws Exception on spring framework dao failure
     */
    public int getAnnotationKeyAndCreationDate(Annotation annot) throws Exception {

        String query = "SELECT a.full_annot_key,created_date FROM full_annot a WHERE "
                // fields that are never null
                +"term_acc=? AND annotated_object_rgd_id=? AND evidence=? AND "
                // fields that could be null
                +"NVL(ref_rgd_id,0) = NVL(?,0) AND "
                +"NVL(with_info,'*') = NVL(?,'*') AND "
                +"NVL(qualifier,'*') = NVL(?,'*') AND "
                +"NVL(xref_source,'*') = NVL(?,'*')";

        final Object[] result = new Object[2];
        MappingSqlQuery q = new MappingSqlQuery(annotDAO.getDataSource(), query) {

            @Override
            protected Object mapRow(ResultSet rs, int i) throws SQLException {
                result[0] = rs.getInt(1);
                result[1] = rs.getTimestamp(2);
                return null;
            }
        };

        annotDAO.execute(q, annot.getTermAcc(), annot.getAnnotatedObjectRgdId(), annot.getEvidence(),
                annot.getRefRgdId(), annot.getWithInfo(), annot.getQualifier(), annot.getXrefSource());
        annot.setCreatedDate((Date)result[1]);
        return result[0]==null ? 0 : (int)result[0];
    }

    // remove records from full_annot
    public int deleteFullAnnot(long startTime, Logger log, String deleteThresholdStr) throws Exception {

        // extract delete threshold in percent
        int percentPos = deleteThresholdStr.indexOf('%');
        int deleteThreshold = Integer.parseInt(deleteThresholdStr.substring(0, percentPos));

        // set cutoff date to be one hour before the pipeline start
        // (to handle the case when app server clock time differs from database server clock time)
        Date cutoffDate = Utils.addHoursToDate(new Date(startTime), -1);

        int currentAnnotCount = annotDAO.getCountOfAnnotationsForCreatedBy(createdBy);
        List<Annotation> annotsForDelete = annotDAO.getAnnotationsModifiedBeforeTimestamp(createdBy, cutoffDate);
        Logger logDelete = Logger.getLogger("deleted");
        for( Annotation a: annotsForDelete ) {
            logDelete.info(a.dump("|"));
        }
        int annotsForDeleteCount = annotsForDelete.size();
        int annotsForDeleteThreshold = (deleteThreshold * currentAnnotCount) / 100; // 5% delete threshold
        if( annotsForDeleteCount > annotsForDeleteThreshold ) {
            log.warn(" STALE ANNOTATIONS DELETE THRESHOLD ("+deleteThresholdStr+") -- "+annotsForDeleteThreshold);
            log.warn(" STALE ANNOTATIONS TAGGED FOR DELETE     -- "+annotsForDeleteCount);
            log.warn(" STALE ANNOTATIONS DELETE THRESHOLD ("+deleteThresholdStr+") EXCEEDED -- no annotations deleted");
            return 0;
        }

        // delete stale annotations
        int rowsDeleted = annotDAO.deleteAnnotations(getCreatedBy(), cutoffDate);
        logger.debug("Rows deleted from full_annot table: "+ rowsDeleted);
        return rowsDeleted;
    }

    /**
     * insert annotation into full_annot table
     * @param fa Annotation object
     * @throws Exception on spring framework dao failure
     */
    public void insertFullAnnot(Annotation fa) throws Exception {

        fa.setRefRgdId(fa.getRefRgdId()==null ? null : fa.getRefRgdId() > 0 ? fa.getRefRgdId() : null);
        fa.setCreatedBy(createdBy);
        fa.setLastModifiedBy(lastModifiedBy);

        annotDAO.insertAnnotation(fa);
    }

    // update LAST_MODIFIED_DATE in batches of up to 1000 rows
    public void initUpdates() {
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
        logger.debug("updated last modified date for "+updated+" rows");
        fullAnnotKeys.clear();
    }
    private List<Integer> _updateLastModified = null;



    public int getRefIdByPubMed(String pubmedId) throws Exception {
        return xdbIdDAO.getRefIdByPubMedId(pubmedId);
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

    /**
     * Check if a term can be used for curation.
     * @param termAcc term ACC id
     * @return true if the term doesn't have a "Not4Curation" synonym
     * @throws Exception if something wrong happens in spring framework
     */
    public boolean isForCuration(String termAcc) throws Exception {
        return ontDAO.isForCuration(termAcc);
    }

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
}
