/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package hu.arezner;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.StringWriter;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.imageio.metadata.IIOMetadataNode;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerConfigurationException;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpression;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;
import org.w3c.dom.DOMException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/**
 *
 * @author attila.rezner
 */
public class FlattenSchemaImports {
    //
    private static final Logger logger = Logger.getLogger("hu.arezner.FlattenSchemaImports");
    private static String schemaFilesDir;
    private static Transformer transformer;
    /**
     * The main attribute sets up javax.xml.transform.Transformer its properties
     * then reads up org.w3c.dom.Document from file,
     * then prints all the original schema,
     * then runs transformation to resolve internal and external type references,
     * then removes other schema references
     * then writes result into file.
     * 
     * @param args
     * @throws TransformerConfigurationException 
     */
    public static void main(String[] args) throws TransformerConfigurationException {     
        schemaFilesDir = args[0];
        // set up Transformer : create instance and set attributes
        transformer = TransformerFactory.newInstance().newTransformer();
        transformer.setOutputProperty(OutputKeys.INDENT, "yes");
        transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");
        transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no");
        transformer.setOutputProperty(OutputKeys.STANDALONE, "yes");
        transformer.setOutputProperty(OutputKeys.METHOD, "xml");
        // read up documents / files name containing Messages
        File[] files = new File(args[0]).listFiles(
            (File schemaFilesDir_, String fileName) -> fileName.toLowerCase().contains("xsd")
        );
        //
        for (File file : files) {
            // read schema from file into xml Document
            Document document = getDocumentFromFile(args[0] +file.getName());        
            // print whole original schema
            logger.log(Level.INFO, "original: {0}", getNodeAsXml(document));
            // replace import type(s) with its definition from its defining schema
            resolveNodes(document.getDocumentElement());
            // 
            removeRootSchemaNsTagsAndImports(document);
            // remove simleType and ComplexType element's name attribute
 //           removeNameAttribute(document);
            // remove annotations
//            removeAnnotations(document);
            // print whole final schema
            logger.log(Level.INFO, "result: {0}", getNodeAsXml(document));
            // write result into file
            writeDocumentToFile(args[0] +"flat_" +file.getName(), document);
        }
    }

    /**
     * Prints a Node in xml format. 
    * 
     * @param transformer
     * @param node 
     * @return String
     */
    private static String getNodeAsXml(Node node) {
        StreamResult streamResult = new StreamResult(new StringWriter());
        try {
            transformer.transform(new DOMSource(node), streamResult);
        } 
        catch (TransformerException e) {
            logger.log(Level.SEVERE, "Node to xml: {0}\n{1}", new Object[] {e.getMessage(), getNodeAsXml(node)});            
        }
        return streamResult.getWriter().toString();
    }    
    
    /**
     * 1  gets a node that has to be resolved
     * 2. gets the node's defining whole schema (rootElement)
     * 3. gets the full schema's all namespace imports, enrolls into nsTag (nsDefSchemaMap)
     * 4. gets all nodes that have imported types
     * 5. walks thru on import type nodes (nodeToResolve)
     * 6.   gets defining schema file name
     * 7.   reads defining schema, gets node type definition (replacementNodeList)
     * 8.   walks thru on node definitions
     * 9.       prints old node (nodeToResolve) and new node def (replacementNode)
     *          removes type attr from old, and appends new as child.
     * 
     * @param node
     */
    private static void resolveNodes(Node node) {        
        // get the document root element of node that has to be replaced.
        Document resolveDocument = node.getOwnerDocument();
        Element rootElement = resolveDocument.getDocumentElement();
        // log node content to resolve
        logger.log(Level.FINE, "To resolve: {0}", getNodeAsXml(resolveDocument));
        //
        resolveInternals(rootElement);
        resolveImports(rootElement);
        resolveExtensions(rootElement);               
    }
    
    /**
     * returns the import nsTags and schema file names
     * 
     * @param node
     * @return Map<String, String>
     */
    private static Map<String, String> getImportNsTagsAndSchemas(Node node) {
        // return : namespace tag and defining schema file name
        Map<String, String> nsDefSchemaMap = new HashMap<>();
        // get the node's defining schema's root element
        Element rootElement = node.getOwnerDocument().getDocumentElement();
        logger.log(Level.FINE, "Imports: {0}", getNodeAsXml(rootElement));
        // get child nodes
        NodeList childNodes = rootElement.getChildNodes();
        // roll over on root child nodes
        for (int i = 0; i < childNodes.getLength(); i++) {
            Node childNode = childNodes.item(i);
            // if import found
            if (childNode.getNodeName().toLowerCase().contains("import")) {
                NamedNodeMap attributes = ((Element)childNode).getAttributes();                                
                String namespace = "", schemaLocation ="";
                for (int j = 0; j < attributes.getLength(); j++) {
                    Node attributeNode = attributes.item(j);
                    if (attributeNode.getNodeName().toLowerCase().equals("namespace")) {
                        namespace = attributeNode.getTextContent();
                    }
                    if (attributeNode.getNodeName().toLowerCase().equals("schemalocation")) {
                        schemaLocation = attributeNode.getTextContent();
                    }
                }
                // then add to map. namespace as key, schema fileName as value
                nsDefSchemaMap.put(namespace, schemaLocation);
            }
        }                
        // get Root element : schema
        rootElement = node.getOwnerDocument().getDocumentElement();
        // get attributes of root element
        NamedNodeMap namedNodeMap = rootElement.getAttributes();
        // walk thru on root node attributes
        for (int i = 0; i < namedNodeMap.getLength(); i++) {
            // root node attribute
            Node rootChild = namedNodeMap.item(i);
            // if long namespace find
            if (nsDefSchemaMap.containsKey(rootChild.getTextContent())) {
                // get defining schema file name of defining namespace
                String schemaFileName = nsDefSchemaMap.get(rootChild.getTextContent());
                // remove old key (long namespace)
                nsDefSchemaMap.remove(rootChild.getTextContent());
                //
                String nodeName = rootChild.getNodeName();
                // add with short namespace tag
                nsDefSchemaMap.put(
                    nodeName.substring(nodeName.indexOf(":") +1), // short namespace tag
                    schemaFileName// schema file name
                );                        
            }
        }
        logger.log(Level.FINE, "Imports: {0}", nsDefSchemaMap);
        // return short namespace - schema file name
        return nsDefSchemaMap;
    }        

    /**
     * Replaces any Node -with imported type definition- with its type definition.
     * 
     * @param transformer
     * @param node
     */
    private static void resolveImports(Node node) {
        Map<String, String> imports = getImportNsTagsAndSchemas(node);
        // get all nodes defined in imported namespaces
        NodeList nodesWithImportedTypes = getImportedNodeList(node, imports.keySet());
        // if there is any importedType
        if (nodesWithImportedTypes != null) { 
            // walk thru on all nodes with imported namespace
            for (int i = 0; i < nodesWithImportedTypes.getLength(); i++) {
                Node nodeToResolve =  nodesWithImportedTypes.item(i);
                // get nsTag of nodeToResolve (type|Type attribute value)
                String typeTagString = ((Element)nodeToResolve).getAttribute("Type") +((Element)nodeToResolve).getAttribute("type");
                if (typeTagString.isEmpty()) {
                    continue;
                }
                // get only nsTag from node name
                typeTagString = typeTagString.substring(0, typeTagString.indexOf(":"));
                // get defining schema name of imported type node
                String definingSchema = imports.get(typeTagString);
                // get node definition from its defining schema 
                NodeList replacementNodeList = getTypeDefinition(nodeToResolve, definingSchema);
                // walk thru on node definition(s) - should be 1 definition.
                for (int j = 0; j < replacementNodeList.getLength(); j++) {
                    // node_ that has name attribute = nsTag
                    Node replacementNode = replacementNodeList.item(j);
                    // print replacement element
                    logger.log(Level.FINE, "replaced: {0}", getNodeAsXml(nodeToResolve));
                    // replace all types in replacementNode
                    resolveInternals(replacementNode);
                    // print new element
                    logger.log(Level.FINE, "replace: {0}", getNodeAsXml(replacementNode));
                    // remove attribute "type" from node that has to be resolved
                    ((Element)nodeToResolve).removeAttribute("Type");
                    ((Element)nodeToResolve).removeAttribute("type");
                    // create new child node under node that has to be resolved
                    Node addedNode = nodeToResolve.appendChild(
                        node.getOwnerDocument().importNode(replacementNode, true)
                    );
                    // resolve newly added node
                    resolveNodes(addedNode);                    
                }                                    
            }
        }
    }

    /**
     * Replaces any extension Node -if any found- with its type definition.
     * 
     * @param transformer
     * @param node
     */
    private static void resolveExtensions(Node node) {
        Map<String, String> imports = getImportNsTagsAndSchemas(node);
        // get all nodes defined as extensions
        NodeList nodesWithExtensionTypes = getExtensionNodeList(node, imports.keySet());
        // if there is any extension to resolve
        if (nodesWithExtensionTypes != null) {
            // walk thru on all nodes with extensions
            for (int i = 0; i < nodesWithExtensionTypes.getLength(); i++) {
                Node nodeToResolve =  nodesWithExtensionTypes.item(i);
                // get nsTag of nodeToResolve (base|Base attribute value)
                String typeTagString = ((Element)nodeToResolve).getAttribute("Base") +((Element)nodeToResolve).getAttribute("base");                                      
                if (typeTagString.isEmpty()) {
                    continue;
                }
                typeTagString = typeTagString.substring(0, typeTagString.indexOf(":"));
                // get defining schema name of imported type node
                String definingSchema = imports.get(typeTagString);
                // get node definition from its defining schema 
                NodeList replacementNodeList = getTypeDefinition(nodeToResolve, definingSchema);
                // walk thru on node definition(s) - should be 1 definition.
                for (int j = 0; j < replacementNodeList.getLength(); j++) {
                    // node_ that has name attribute = nsTag
                    Node replacementNode = replacementNodeList.item(j);
                    // print replacement element
                    logger.log(Level.FINE, "replaced: {0}", getNodeAsXml(nodeToResolve));
                    // print new element
                    logger.log(Level.FINE, "replace: {0}", getNodeAsXml(replacementNode));
                    // remove attribute "base" from node that has to be resolved
                    ((Element)nodeToResolve).removeAttribute("Base");
                    ((Element)nodeToResolve).removeAttribute("base");                   
                    // create new child node under node that has to be resolved                    
                    Node addedNode = nodeToResolve.appendChild(
                        node.getOwnerDocument().importNode(replacementNode, true)
                    ); 
                    // resolve newly added node
                    resolveNodes(addedNode);
                }                                    
            }
        }
    }
    
    /**
     * Resolve type definitions inside the same schema instance.
     * Search for simpleType or complexType rootChild element that are referenced 
     * from an element type attribute. If found replaces the ref with type def.
     * 
     * @param node 
     */
    private static void resolveInternals(Node node) {
        // print node content that is under resolving
        logger.log(Level.FINE, "Resolve internal type: {0}", getNodeAsXml(node.getOwnerDocument()));        
        // node list contains nodes with types locally defined        
        NodeList nodeListWithoutNs = getNodeListWithXpath(node, "//*[@type and not(contains(@type, ':'))]");
        // if any found
        if (nodeListWithoutNs != null) {
            // walk thru on nodes whose type defined locally
            for (int i = 0; i < nodeListWithoutNs.getLength(); i++) {
                // take a node where type defined locally
                Node nodeWithoutNs = nodeListWithoutNs.item(i);
                // get its type name reference
                String nodeWithoutNsTypeAttr = ((Element)nodeWithoutNs).getAttribute("Type") +((Element)nodeWithoutNs).getAttribute("type");
                // find the type definition node
                NodeList nodeListLocalTypes = getNodeListWithXpath(node, "//*[local-name()='complexType' or local-name()='simpleType'][@name='" +nodeWithoutNsTypeAttr +"']");
                // 1st item found for the type definition
                Node nodeLocalType = nodeListLocalTypes.item(0);
                //
                if (nodeLocalType != null) {
                    // print node referencing to locally defined type
                    logger.log(Level.FINE, "localOld: {0}", getNodeAsXml(nodeWithoutNs));
                    // print local node type definition 
                    logger.log(Level.FINE, "localNew: {0}", getNodeAsXml(nodeLocalType));                                 
                    try {
                        // remove type attribute
                        ((Element)nodeWithoutNs).removeAttribute("Type");
                        ((Element)nodeWithoutNs).removeAttribute("type");
                        // insert type definiton as child under type referencing node
                        Node addedNode = nodeWithoutNs.appendChild(nodeLocalType);
                        // resolve newly added node
                        resolveNodes(addedNode);
                    } 
                    catch (DOMException e) {
                        logger.log(Level.SEVERE, "dom error: {0}\nresolved node: {1}\nadded node: {2}", new Object[] { e.getMessage(), getNodeAsXml(nodeWithoutNs), getNodeAsXml(nodeLocalType) });
                    }
                }
            }
        }
    }
    
    /**
     * Returns Nodes from document where child type attribute equals any of 
     * import namespace short nsTag.
     * 
     * @param node
     * @param nsTags
     * @return NodeList
     */
    private static NodeList getImportedNodeList(Node node, Set<String> nsTags) {
        // 
        if (!nsTags.isEmpty()) {
            StringBuffer nsTagsXpath = new StringBuffer();        
            //
            Iterator<String> nsTagsIter = nsTags.iterator();
            while (nsTagsIter.hasNext()) {
                String nsTag = nsTagsIter.next();
                //
                nsTagsXpath.append("(starts-with(@type, '").append(nsTag).append(":')").append(") or ");
            }
            //
            nsTagsXpath.delete(nsTagsXpath.lastIndexOf(" or "), nsTagsXpath.length());
            //
            return getNodeListWithXpath(node, "//*[" +nsTagsXpath +"]");
        }
        return new IIOMetadataNode();
    }    

    /**
     * Returns Nodes from document where child base attribute equals any of 
     * import namespace short nsTag.
     * 
     * @param node
     * @param nsTags
     * @return NodeList
     */
    private static NodeList getExtensionNodeList(Node node, Set<String> nsTags) {
        // 
        if (!nsTags.isEmpty()) {
            StringBuffer nsTagsXpath = new StringBuffer();        
            //
            Iterator<String> nsTagsIter = nsTags.iterator();
            while (nsTagsIter.hasNext()) {
                String nsTag = nsTagsIter.next();
                //
                nsTagsXpath.append("(starts-with(@base, '").append(nsTag).append(":')").append(") or ");
            }
            nsTagsXpath.delete(nsTagsXpath.lastIndexOf(" or "), nsTagsXpath.length());
            //
            return getNodeListWithXpath(node, "//*[" +nsTagsXpath +"]");            
        }
        return new IIOMetadataNode();
    }    
    
    /**
     * Gets a schema document object from a file identified by name.
     * 
     * @param xmlFileName
     * @return Document
     */
    private static Document getDocumentFromFile(String xmlFileName) {
        //
        DocumentBuilderFactory builderFactory = DocumentBuilderFactory.newInstance();
        builderFactory.setIgnoringElementContentWhitespace(true);
        try {
            DocumentBuilder builder = builderFactory.newDocumentBuilder();
            Document document = builder.parse(new FileInputStream(xmlFileName));
            return document;
        } 
        catch (IOException | ParserConfigurationException | SAXException e) {
            logger.log(Level.SEVERE, "error: {0}\nxml file: {1}", new Object[] { e.getMessage(), xmlFileName });            
            return null;
        } 
    }
    
    /**
     * get the node from its defining schema. Param node passed for getting its 
     * type attribute's nsTag that is searched in its defining schema.
     * 
     * @param node
     * @param schema
     * @return NodeList
     */
    private static NodeList getTypeDefinition(Node node, String schema) {        
        // read the source schema
        Document document = getDocumentFromFile(schemaFilesDir +schema);
        // defining document root of node 
        Element rootElement = document.getDocumentElement();
        // get the type name of node without the nsTag
        String typeTagString = ((Element)node).getAttribute("Type") + ((Element)node).getAttribute("Base") + ((Element)node).getAttribute("type") + ((Element)node).getAttribute("base");
        // get the node name        
        typeTagString = typeTagString.substring(typeTagString.indexOf(":") +1);
        // 
        return getNodeListWithXpath(rootElement, "//*[@name='" +typeTagString +"']");
    }
    
    /**
     * 
     * 
     * @param node
     * @param xpathString
     * @return NodeList
     */
    private static NodeList getNodeListWithXpath(Node node, String xpathString) {
        XPath xPath = XPathFactory.newInstance().newXPath();
        // get local type ref nodes
        try {
            XPathExpression xExpress = xPath.compile(xpathString);
            return ((NodeList)xExpress.evaluate(node, XPathConstants.NODESET));            
        } 
        catch (XPathExpressionException e) {
            logger.log(Level.SEVERE, "xpath error: {0}\nxml: {1}", new Object[] { e.getMessage(), getNodeAsXml(node) });
            return null;
        }
    }
    
    /**
     * 
     * 
     * @param xmlFileName
     * @param node 
     */
    private static void writeDocumentToFile(String xmlFileName, Node node) {
        //        
        try (FileOutputStream fos = new FileOutputStream(xmlFileName)) {                        
            fos.write(getNodeAsXml(node).getBytes());
        } 
        catch (IOException e) {
            logger.log(Level.SEVERE, e.getMessage());            
        }        
    }

    /**
     * 
     * 
     * @param node 
     */
    private static void removeRootSchemaNsTagsAndImports(Node node) {
        //
        NodeList schemaNodeList = getNodeListWithXpath(node, "//*[local-name()='schema']");
        // expression: all nodes with type attr not contains 'import'
        NodeList importNodeList = getNodeListWithXpath(node, "//*[local-name()='import']");
        // if there is any import node
        if (importNodeList != null) {
            // walk thru on import nodes and remove namespace Attribute from its parent schema node
            for (int i = 0; i < importNodeList.getLength(); i++) {
                String nsTag = ((Element)importNodeList.item(i)).getAttribute("Namespace") + ((Element)importNodeList.item(i)).getAttribute("namespace");
                // if there is any schema node
                if (schemaNodeList != null) {
                    // 
                    for (int k = 0; k < schemaNodeList.getLength(); k++) {
                        // get schema Node's attributes
                        NamedNodeMap schemaNodeAttributes = ((Element)schemaNodeList.item(k)).getAttributes();
                        //
                        for (int j = 0; j < schemaNodeAttributes.getLength(); j++) {
                            Node attribute = (Node)schemaNodeAttributes.item(j);
                            //
                            if (attribute.getNodeValue().equals(nsTag)) {
                                // remove namwspace attribute from schema
                                ((Element)schemaNodeList.item(k)).removeAttribute(attribute.getNodeName());
                                // remove import element
                                schemaNodeList.item(k).removeChild(importNodeList.item(i));
                            }
                        }
                    }
                }
            }
        }
    }
    
    /**
     * actually not used.
     * 
     * @param node 
     */
    private static void removeNameAttribute(Node node) {
        // 
        NodeList nodeList = getNodeListWithXpath(node, "//*[local-name()='simpleType' or local-name()='complexType']");
        // if there is any node
        if (nodeList != null) {
            // walk thru on NodeList and remove Attribute = 'name'
            for (int i = 0; i < nodeList.getLength(); i++) {
                ((Element)nodeList.item(i)).removeAttribute("Name");
                ((Element)nodeList.item(i)).removeAttribute("name");
            }
        }
    }

    /**
     * actually not used.
     * 
     * @param node 
     */
    private static void removeAnnotations(Node node ) {  
        // expression: all nodes with type attr not contains 'xs:'
        NodeList nodeList = getNodeListWithXpath(node, "//[local-name()='annotation']");
        // if there is any node
        if (nodeList != null) {
            // walk thru on NodeList and remove Attribute = 'name'
            for (int i = 0; i < nodeList.getLength(); i++) {
                Node parentNode = nodeList.item(i).getParentNode();
                try {
                    parentNode.removeChild(nodeList.item(i));
                } 
                catch (DOMException e)  {
                    logger.log(Level.SEVERE, e.getMessage());                    
                }
            }
        }
    }
    
}