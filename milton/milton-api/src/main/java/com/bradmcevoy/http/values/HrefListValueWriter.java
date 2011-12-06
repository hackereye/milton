package com.bradmcevoy.http.values;

import com.bradmcevoy.http.XmlWriter;
import com.bradmcevoy.http.XmlWriter.Element;
import com.bradmcevoy.http.webdav.WebDavProtocol;
import java.util.Map;

/**
 * Supports HrefList objects, and writes them out as a list of <href>...</href> elements
 *
 * Currently readonly, but should support writing
 *
 * @author brad
 */
public class HrefListValueWriter implements ValueWriter {

	@Override
	public boolean supports(String nsUri, String localName, Class c) {
		return HrefList.class.isAssignableFrom(c);
	}

	@Override
	public void writeValue(XmlWriter writer, String nsUri, String prefix, String localName, Object val, String href, Map<String, String> nsPrefixes) {
		Element outerEl = writer.begin(prefix, localName).open();
		HrefList list = (HrefList) val;
		if (list != null) {
			for (String s : list) {
				Element hrefEl = writer.begin( WebDavProtocol.DAV_PREFIX + ":href").open(false);
				hrefEl.writeText(s);
				hrefEl.close();
			}
		}
		outerEl.close();
	}

	@Override
	public Object parse(String namespaceURI, String localPart, String value) {
		throw new UnsupportedOperationException("Not supported yet.");
	}
}
