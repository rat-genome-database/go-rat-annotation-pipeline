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
			String annotKey = createAnnotKey(a);
			Annotation aOld = _cacheMap.put(annotKey, a);
			if( aOld!=null ) {
				System.out.println("unexpected: duplicate annot");
				System.out.println("   "+aOld.dump("|"));
				System.out.println("   "+a.dump("|"));
			}
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
		return _cacheMap.get(annotKey);
	}

	public void insert(Annotation a) throws Exception {
		String annotKey = createAnnotKey(a);
		Annotation oldAnnot = _cacheMap.put(annotKey, a);
		if( oldAnnot!=null ) {
			throw new Exception("unexpected: duplicate annot: "+a.dump("|"));
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
