package edu.mcw.rgd.goa;

import java.util.Map;

/**
 * @author JChen
 * Created on Jan 29, 2007
 */
public class RatGeneAssoc {
	
	String db;
	String dbObjectId;
	String dbObjectSymbol;
	String qualifier;
	String goId;
	String dbReferences;
	int refRgdId;
	String evidence;
	String with;
	String aspect;
	String dbObjectName;
	String synonym;
	String dbObjectType;
	String taxonId;
	String createdDate;
	String assignedBy;
    String annotationExtension;
    String geneProductFormId; // i.e. UniProtKB:P12345-2

    public boolean ignoreMissingInRgdRef;

	public String getAspect() {
		return aspect;
	}
	
	public void setAspect(String aspect) {
		this.aspect = aspect;
	}
	
	public String getAssignedBy() {
		return assignedBy;
	}

	/**
	 *
	 * @param assignedBy
	 * @param substMap
     * @return true if 'assignedBy' was set to a substitute from the provided map
     */
	public boolean setAssignedBy(String assignedBy, Map<String,String> substMap) {
		if( substMap!=null ) {
			String assignedBySubst = substMap.get(assignedBy);
			if( assignedBySubst!=null ) {
				this.assignedBy = assignedBySubst;
				return true;
			}
		}
		this.assignedBy = assignedBy;
		return false;
	}
	
	public String getCreatedDate() {
		return createdDate;
	}
	
	public void setCreatedDate(String createdDate) {
		this.createdDate = createdDate;
	}
	
	public String getDb() {
		return db;
	}
	
	public void setDb(String db) {
		this.db = db;
	}
	
	public String getDbObjectId() {
		return dbObjectId;
	}
	/**
	 * @param dbObjectId The dbObjectId to set.
	 */
	public void setDbObjectId(String dbObjectId) {
		this.dbObjectId = dbObjectId;
	}
	
	public String getDbObjectName() {
		return dbObjectName;
	}
	
	public void setDbObjectName(String dbObjectName) {
		this.dbObjectName = dbObjectName;
	}
	
	public String getDbObjectSymbol() {
		return dbObjectSymbol;
	}
	
	public void setDbObjectSymbol(String dbObjectSymbol) {
		this.dbObjectSymbol = dbObjectSymbol;
	}
	
	public String getDbObjectType() {
		return dbObjectType;
	}
	
	public void setDbObjectType(String dbObjectType) {
		this.dbObjectType = dbObjectType;
	}
	
	public String getDbReferences() {
		return dbReferences;
	}
	
	public void setDbReferences(String dbReferences) {
		this.dbReferences = dbReferences;
	}

	public int getRefRgdId() {
		return refRgdId;
	}

	public void setRefRgdId(int refRgdId) {
		this.refRgdId = refRgdId;
	}

	public String getEvidence() {
		return evidence;
	}
	
	public void setEvidence(String evidence) {
		this.evidence = evidence;
	}
	
	public String getGoId() {
		return goId;
	}
	
	public void setGoId(String goId) {
		this.goId = goId;
	}
	
	public String getQualifier() {
		return qualifier;
	}
	
	public void setQualifier(String qualifier) {
		this.qualifier = qualifier;
	}
	
	public String getSynonym() {
		return synonym;
	}
	
	public void setSynonym(String synonym) {
		this.synonym = synonym;
	}
	
	public String getTaxonId() {
		return taxonId;
	}
	
	public void setTaxonId(String taxonId) {
		this.taxonId = taxonId;
	}
	
	public String getWith() {
		return with;
	}
	
	public void setWith(String with) {
		this.with = with;
	}

    public String getAnnotationExtension() {
        return annotationExtension;
    }

    public void setAnnotationExtension(String annotationExtension) {
        this.annotationExtension = annotationExtension;
    }

    public String getGeneProductFormId() {
        return geneProductFormId;
    }

    public void setGeneProductFormId(String geneProductFormId) {
        this.geneProductFormId = geneProductFormId;
    }

}
