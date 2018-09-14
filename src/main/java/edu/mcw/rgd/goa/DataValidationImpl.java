package edu.mcw.rgd.goa;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import edu.mcw.rgd.datamodel.GenomicElement;
import edu.mcw.rgd.datamodel.ontology.Annotation;
import edu.mcw.rgd.process.Utils;

import org.apache.log4j.Logger;

/**
 * Created on Jan 30, 2007
 * @author JChen
 *
 */
public class DataValidationImpl {
	
	private Dao dao;
    private Set<String> obsoleteRelations; // obsolete relations loaded from gorel.obo
    private int annotationsWithRemovedObsoleteRelations = 0;
    private int countOfRemovedObsoleteRelations = 0;

    private Logger logObsoleteRelInAnnotExt = Logger.getLogger("obsoleteRelInAnnotExt");

    public List<GenomicElement> checkDbObjectId (RatGeneAssoc rga) throws Exception {
		ArrayList<GenomicElement> rgdinfo=new ArrayList<>();
		int xdbKey=0;
		String db=rga.getDb();
        boolean usedGeneProductFormId = false;

        // To be compatible with GAF version 1.0 and the upcoming 2.0 version (rene lopez)
        String dbObjectId;
        if (rga.getGeneProductFormId() != null && !rga.getGeneProductFormId().equals("")) {
            String a_DbObjectId[] = rga.getGeneProductFormId().split(":");
            if (a_DbObjectId.length >= 2) {
                dbObjectId = a_DbObjectId[1];
                usedGeneProductFormId = true;
            } else {
                dbObjectId = rga.getDbObjectId();
            }
        } else {
            dbObjectId = rga.getDbObjectId();
        }
        if ( dbObjectId == null || dbObjectId.equals("")) {
            dbObjectId = rga.getDbObjectId();
            usedGeneProductFormId = false;
        }
        if(db.equalsIgnoreCase("UniProtKB") || db.equalsIgnoreCase("UniProtKB/Swiss-Prot") || db.equalsIgnoreCase("UniProtKB/TrEMBL")) {
            xdbKey = 14;
		}
		else if(db.equalsIgnoreCase("RefSeq")) {
            xdbKey = 7;
		}
		else if(db.equalsIgnoreCase("ENSEMBL")) {
            xdbKey = 27;
		}

        List<GenomicElement> geneRgdInfo=dao.queryGeneRgdInfoByAccid(dbObjectId,xdbKey);
        if (usedGeneProductFormId && geneRgdInfo.isEmpty() ) {
            geneRgdInfo=dao.queryGeneRgdInfoByAccid(rga.getDbObjectId(),xdbKey);
        }
		// multiple rgdids may be returned

		// remove non-active genes
        for (GenomicElement ge: geneRgdInfo) {
            if( ge.getObjectStatus().equalsIgnoreCase("ACTIVE") ) {
                rgdinfo.add(ge);
            }
        }
		return rgdinfo;
	}

    /**
     * check if given annotation is already in RGD
     * @param annot Annotation object
     * @return full_annot_key if the incoming object is unique (not a duplicate), or 0 otherwise
     * @throws Exception
     */
	public int checkDuplications(Annotation annot) throws Exception {
		return dao.getAnnotationKeyAndCreationDate(annot);
	}

    public void validateExtensionRelations(RatGeneAssoc rga) {

        // check to see if there any extension relations in the annotation
        if( Utils.isStringEmpty(rga.getAnnotationExtension()) ) {
            return;
        }

        // ensure none of the relations, listed in annotation extension field, is obsolete
        // if it is obsolete, it will be deleted
        List<String> obsoleteAnnotExts = null;
        for( String annotExt: rga.getAnnotationExtension().split("[\\,\\|]") ) {
            // annotation extension is like this: 'dependent_on(CHEBI:15422)'
            // so extRel is 'dependent_on'
            String extRel = annotExt.substring(0, annotExt.indexOf('(')).trim();
            if( getObsoleteRelations().contains(extRel) ) {
                // an obsolete relation was found
                if( obsoleteAnnotExts==null ) {
                    obsoleteAnnotExts = new ArrayList<>();
                }
                obsoleteAnnotExts.add(annotExt);
                countOfRemovedObsoleteRelations++;
            }
        }

        // remove obsolete annot exts
        if( obsoleteAnnotExts!=null ) {
            String newAnnotExt = rga.getAnnotationExtension();
            for( String obsoleteAnnotExt: obsoleteAnnotExts ) {
                newAnnotExt = newAnnotExt.replace(obsoleteAnnotExt, "");
            }

            // remove extra punctuation
            while( newAnnotExt.startsWith(",") || newAnnotExt.startsWith("|") ) {
                newAnnotExt = newAnnotExt.substring(1);
            }
            while( newAnnotExt.endsWith(",") || newAnnotExt.endsWith("|") ) {
                newAnnotExt = newAnnotExt.substring(0, newAnnotExt.length()-1);
            }
            while( newAnnotExt.contains(",,") ) {
                newAnnotExt = newAnnotExt.replace(",,", ",");
            }
            while( newAnnotExt.contains(",|") ) {
                newAnnotExt = newAnnotExt.replace(",|", "|");
            }
            while( newAnnotExt.contains("||") ) {
                newAnnotExt = newAnnotExt.replace("||", "|");
            }
            while( newAnnotExt.contains("|,") ) {
                newAnnotExt = newAnnotExt.replace("|,", "|");
            }

            logObsoleteRelInAnnotExt.info("obsolete relations removed from annotation extension: '"
                    +rga.getAnnotationExtension()+"'==>'"+newAnnotExt
                    +"' "+rga.getDb()+" "+rga.getDbObjectId()+" "+rga.getDbObjectSymbol()+" "+rga.getGoId()
                    +" "+rga.getDbReferences()+" "+rga.getEvidence());

            rga.setAnnotationExtension(newAnnotExt);
            annotationsWithRemovedObsoleteRelations++;
        }
    }

    /**
     * @param dao The Dao to set.
     */
    public void setDao(Dao dao) {
        this.dao = dao;
    }

    public Set<String> getObsoleteRelations() {
        return obsoleteRelations;
    }

    public void setObsoleteRelations(Set<String> obsoleteRelations) {
        this.obsoleteRelations = obsoleteRelations;
    }

    public int getCountOfRemovedObsoleteRelations() {
        return countOfRemovedObsoleteRelations;
    }

    public int getAnnotationsWithRemovedObsoleteRelations() {
        return annotationsWithRemovedObsoleteRelations;
    }
}
