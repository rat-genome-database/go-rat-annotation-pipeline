package edu.mcw.rgd.goa;


import edu.mcw.rgd.datamodel.ontology.Annotation;
import edu.mcw.rgd.process.Utils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * @author mtutaj
 * @since 2020-03-03
 */
public class AnnotCache {

	private Map<String, Annotation> _cacheMap = new HashMap<>();

	/**
	 * load pipeline annotations from the database
	 * @return count of annotations loaded
     */
	public int loadAnnotations(Dao dao) throws Exception {

		List<Annotation> annotations = dao.getAllAnnotations();
		for( Annotation a: annotations ) {
			insert(a);
		}
		return annotations.size();
	}

	/**
	 *
	 * @param a incoming annotation (requires REF_RGD_ID, RGD_ID, TERM_ACC, XREF_SOURCE, QUALIFIER, WITH_INFO, EVIDENCE to be set
	 * @return Annotation object in RGD, or null
     */
	public Annotation getAnnotInRgd(Annotation a) {
		String annotKey = createAnnotKey(a);

		Annotation annotInRgd = _cacheMap.get(annotKey);
		if( annotInRgd!=null ) {
			int rgdDiff = annotInRgd.getAnnotatedObjectRgdId() - a.getAnnotatedObjectRgdId();
			if( rgdDiff!=0 ) {
				throw new RuntimeException("cache consistency problem");
			}
		}
		return _cacheMap.get(annotKey);
	}

	public void insert(Annotation a) throws Exception {
		String annotKey = createAnnotKey(a);

		// to avoid corruption of the hashmap, we clone the annotation inserted
		Annotation aCopy = (Annotation) a.clone();

		Annotation oldAnnot = _cacheMap.put(annotKey, aCopy);
		if( oldAnnot!=null ) {
			System.out.println("unexpected: duplicate annot");
			System.out.println("   "+oldAnnot.dump("|"));
			System.out.println("   "+a.dump("|"));
		}
	}

	private String createAnnotKey(Annotation a) {
		return a.getRefRgdId()+"|"+a.getAnnotatedObjectRgdId()+"|"+a.getTermAcc()
				+"|" + Utils.defaultString(a.getXrefSource())
				+"|" + Utils.defaultString(a.getQualifier())
				+"|" + Utils.defaultString(a.getWithInfo())
				+"|" + Utils.defaultString(a.getEvidence());
	}
}
