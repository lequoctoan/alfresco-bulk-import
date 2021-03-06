/*
 * Copyright (C) 2007-2013 Peter Monks.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *     http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * 
 * This file is part of an unsupported extension to Alfresco.
 * 
 */

package org.alfresco.extension.bulkimport.impl;

import java.io.Serializable;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.alfresco.model.ContentModel;
import org.alfresco.repo.policy.BehaviourFilter;
import org.alfresco.repo.security.authentication.AuthenticationUtil;
import org.alfresco.repo.security.authentication.AuthenticationUtil.RunAsWork;
import org.alfresco.repo.transaction.RetryingTransactionHelper;
import org.alfresco.repo.transaction.RetryingTransactionHelper.RetryingTransactionCallback;
import org.alfresco.repo.version.VersionModel;
import org.alfresco.service.ServiceRegistry;
import org.alfresco.service.cmr.repository.ContentService;
import org.alfresco.service.cmr.repository.ContentWriter;
import org.alfresco.service.cmr.repository.InvalidNodeRefException;
import org.alfresco.service.cmr.repository.NodeRef;
import org.alfresco.service.cmr.repository.NodeService;
import org.alfresco.service.cmr.repository.StoreRef;
import org.alfresco.service.cmr.version.VersionService;
import org.alfresco.service.cmr.version.VersionType;
import org.alfresco.service.namespace.NamespaceService;
import org.alfresco.service.namespace.QName;

import org.alfresco.extension.bulkimport.source.BulkImportItem;
import org.alfresco.extension.bulkimport.source.BulkImportItem.Version;

/**
 * This class implements the logic for importing a batch into Alfresco.
 *
 * @author Peter Monks (pmonks@gmail.com)
 *
 */
public class BatchImporterImpl
    implements BatchImporter
{
    private final static Log log = LogFactory.getLog(BatchImporterImpl.class);
    
    private final static String COUNTER_NODES_CREATED           = "Nodes created";
    private final static String COUNTER_NODES_UPDATED           = "Nodes updated";
    private final static String COUNTER_NODES_SKIPPED           = "Nodes skipped";
    private final static String COUNTER_VERSIONS_CREATED        = "Versions created";
    private final static String COUNTER_ASPECTS_ASSOCIATED      = "Aspects associated";
    private final static String COUNTER_PROPERTIES_POPULATED    = "Properties populated";
    private final static String COUNTER_IN_PLACE_CONTENT_LINKED = "In place content linked";
    private final static String COUNTER_CONTENT_STREAMED_BYTES  = "Content streamed (bytes)";
    private final static String COUNTER_CONTENT_STREAMED        = "Content streamed";
    
    private final static String[] COUNTERS = { COUNTER_NODES_CREATED,
                                               COUNTER_NODES_UPDATED,
                                               COUNTER_NODES_SKIPPED,
                                               COUNTER_VERSIONS_CREATED,
                                               COUNTER_ASPECTS_ASSOCIATED,
                                               COUNTER_PROPERTIES_POPULATED,
                                               COUNTER_IN_PLACE_CONTENT_LINKED,
                                               COUNTER_CONTENT_STREAMED_BYTES,
                                               COUNTER_CONTENT_STREAMED };
            
    private final ServiceRegistry   serviceRegistry;
    private final BehaviourFilter   behaviourFilter;
    private final NodeService       nodeService;
    private final VersionService    versionService;
    private final ContentService    contentService;
    
    private final WritableBulkImportStatus importStatus;
    
    
    public BatchImporterImpl(final ServiceRegistry          serviceRegistry,
                             final BehaviourFilter          behaviourFilter,
                             final WritableBulkImportStatus importStatus)
    {
        // PRECONDITIONS
        assert serviceRegistry != null : "serviceRegistry must not be null.";
        assert behaviourFilter != null : "behaviourFilter must not be null.";
        assert importStatus    != null : "importStatus must not be null.";
        
        // Body
        this.serviceRegistry = serviceRegistry;
        this.behaviourFilter = behaviourFilter;
        this.importStatus    = importStatus;
        
        this.nodeService    = serviceRegistry.getNodeService();
        this.versionService = serviceRegistry.getVersionService();
        this.contentService = serviceRegistry.getContentService();
        
        importStatus.preregisterTargetCounters(COUNTERS);
    }
    

    /**
     * @see org.alfresco.extension.bulkimport.impl.BatchImporter#importBatch(java.lang.String, org.alfresco.service.cmr.repository.NodeRef, java.util.List, boolean, boolean)
     */
    @Override
    public final void importBatch(final String               userId,
                                  final NodeRef              target,
                                  final List<BulkImportItem> batch,
                                  final boolean              replaceExisting,
                                  final boolean              dryRun)
        throws InterruptedException
    {
        AuthenticationUtil.runAs(new RunAsWork<Object>()
        {
            @Override
            public Object doWork()
                throws Exception
            {
                importBatchInTxn(target, batch, replaceExisting, dryRun);
                return(null);
            }
        }, userId);
    }

    
    private final void importBatchInTxn(final NodeRef              target,
                                        final List<BulkImportItem> batch,
                                        final boolean              replaceExisting,
                                        final boolean              dryRun)
        throws InterruptedException
    {
        RetryingTransactionHelper txnHelper = serviceRegistry.getRetryingTransactionHelper();

        txnHelper.doInTransaction(new RetryingTransactionCallback<Object>()
        {
            @Override
            public Object execute()
                throws Exception
            {
                // Disable the auditable aspect's behaviours for this transaction, to allow creation & modification dates to be set
                behaviourFilter.disableBehaviour(ContentModel.ASPECT_AUDITABLE);
                
                if (batch != null)
                {
                    for (final BulkImportItem item : batch)
                    {
                        if (Thread.currentThread().isInterrupted()) throw new InterruptedException(Thread.currentThread().getName() + " was interrupted. Terminating early.");
                        
                        importItem(target, item, replaceExisting, dryRun);
                    }
                }
                
                return(null);
            }
        },
        false,   // read only flag, false=R/W txn
        false);  // requires new txn flag, false=does not require a new txn if one is already in progress
        
        importStatus.batchCompleted(batch);
    }
    
    
    private final void importItem(final NodeRef          target,
                                  final BulkImportItem   item,
                                  final boolean          replaceExisting,
                                  final boolean          dryRun)
        throws InterruptedException
    {
        if (log.isDebugEnabled()) log.debug("Importing " + String.valueOf(item));
        
        NodeRef nodeRef     = findOrCreateNode(target, item, replaceExisting, dryRun);
        boolean isDirectory = item.isDirectory();
        
        if (nodeRef != null)
        {
            // We're createing or replacing the item, so import it
            if (isDirectory)
            {
                importDirectory(nodeRef, item, dryRun);
            }
            else
            {
                importFile(nodeRef, item, dryRun);
            }
        }
    }
    
    
    private final NodeRef findOrCreateNode(final NodeRef        target,
                                           final BulkImportItem item,
                                           final boolean        replaceExisting,
                                           final boolean        dryRun)
    {
        NodeRef result           = null;
        String  nodeName         = item.getName();
        String  nodeNamespace    = item.getNamespace();
        QName   nodeQName        = QName.createQName(nodeNamespace == null ? NamespaceService.CONTENT_MODEL_1_0_URI : nodeNamespace,
                                                     QName.createValidLocalName(nodeName));
        boolean isDirectory      = item.isDirectory();
        String  parentAssoc      = item.getParentAssoc();
        QName   parentAssocQName = parentAssoc == null ? ContentModel.ASSOC_CONTAINS : QName.createQName(parentAssoc);
        NodeRef parentNodeRef    = item.getParent(target) == null ? target : item.getParent(target);
        
        // Find the node
        if (log.isDebugEnabled()) log.debug("Searching for node with name '" + nodeName + "' within node '" + String.valueOf(parentNodeRef) + "' with parent association '" + String.valueOf(parentAssocQName) + "'.");
        result = nodeService.getChildByName(parentNodeRef, parentAssocQName, nodeName);
        
        if (result == null)    // We didn't find it, so create a new node in the repo. 
        {
            String itemType      = item.getVersions().first().getType();
            QName  itemTypeQName = itemType == null ? (isDirectory ? ContentModel.TYPE_FOLDER : ContentModel.TYPE_CONTENT) : QName.createQName(itemType);

            if (dryRun)
            {
                if (log.isInfoEnabled()) log.info("[DRY RUN] Would have created new node of type '" + String.valueOf(itemTypeQName) + "' with qname '" + String.valueOf(nodeQName) + "' within node '" + String.valueOf(parentNodeRef) + "' with parent association '" + String.valueOf(parentAssocQName) + "'.");
                result = new NodeRef(StoreRef.STORE_REF_WORKSPACE_SPACESSTORE, "dry-run-fake-created-node-ref");
            }
            else
            {
                if (log.isDebugEnabled()) log.debug("Creating new node of type '" + String.valueOf(itemTypeQName) + "' with qname '" + String.valueOf(nodeQName) + "' within node '" + String.valueOf(parentNodeRef) + "' with parent association '" + String.valueOf(parentAssocQName) + "'.");
                result = nodeService.createNode(parentNodeRef, parentAssocQName, nodeQName, itemTypeQName).getChildRef();
            }
            
            importStatus.incrementTargetCounter(COUNTER_NODES_CREATED);
        }
        else if (replaceExisting)
        {
            if (log.isDebugEnabled()) log.debug("Found content node '" + String.valueOf(result) + "'.");
            importStatus.incrementTargetCounter(COUNTER_NODES_UPDATED);
        }
        else
        {
            if (log.isInfoEnabled()) log.info("Skipping '" + item.getName() + "' as it already exists in the repository and 'replace existing' is false.");
            result = null;
            importStatus.incrementTargetCounter(COUNTER_NODES_SKIPPED);
        }
        
        return(result);
    }
    

    private final void importDirectory(final NodeRef        nodeRef,
                                       final BulkImportItem item,
                                       final boolean        dryRun)
        throws InterruptedException
    {
        if (item.getVersions() != null &&
            item.getVersions().size() > 0)
        {
            if (item.getVersions().size() > 1)
            {
                log.warn("Skipping versions for directory '" + item.getName() + "' - Alfresco does not support versioned spaces.");
            }
            
            Version lastVersion = item.getVersions().last();

            if (lastVersion.hasContent())
            {
                log.warn("Skipping content for directory '" + item.getName() + "' - Alfresco doesn't support content in spaces.");
            }
            
            // Import the last version's metadata only
            importVersionMetadata(nodeRef, lastVersion, dryRun);
        }
        else
        {
            if (log.isDebugEnabled()) log.debug("No metadata to import for directory '" + item.getName() + "'.");
        }
    }


    private final void importFile(final NodeRef        nodeRef,
                                  final BulkImportItem item,
                                  final boolean        dryRun)
        throws InterruptedException
    {
        BulkImportItem.Version previousVersion = null;
        
        for (final Version version : item.getVersions())
        {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException(Thread.currentThread().getName() + " was interrupted. Terminating early.");
            
            importVersion(nodeRef, previousVersion, version, dryRun);
            previousVersion = version;
        }
    }
    
    
    private final void importVersion(final NodeRef                nodeRef,
                                     final BulkImportItem.Version previousVersion,
                                     final BulkImportItem.Version version,
                                     final boolean                dryRun)
        throws InterruptedException
    {
        Map<String, Serializable> versionProperties = new HashMap<String, Serializable>();
        boolean                   isMajor           = true;
        
        importVersionContentAndMetadata(nodeRef, version, dryRun);
        
        if (previousVersion != null)
        {
            isMajor = version.getVersionNumber().intValue() - previousVersion.getVersionNumber().intValue() > 0;
        }

        // Note: PROP_VERSION_LABEL is a "reserved" property, and cannot be modified by custom code.
        // In other words, we can't use the source's version label as the version label in Alfresco.  :-(
        // See: https://github.com/pmonks/alfresco-bulk-import/issues/13
//        versionProperties.put(ContentModel.PROP_VERSION_LABEL.toString(), String.valueOf(version.getVersionNumber().toString()));
        versionProperties.put(VersionModel.PROP_VERSION_TYPE, isMajor ? VersionType.MAJOR : VersionType.MINOR);
        
        if (dryRun)
        {
            if (log.isInfoEnabled()) log.info("[DRY RUN] Would have created " + (isMajor ? "major" : "minor") + " version of node '" + String.valueOf(nodeRef) + "'.");
        }
        else
        {
            if (log.isDebugEnabled()) log.debug("Creating " + (isMajor ? "major" : "minor") + " version of node '" + String.valueOf(nodeRef) + "'.");
            versionService.createVersion(nodeRef, versionProperties);
        }
        
        importStatus.incrementTargetCounter(COUNTER_VERSIONS_CREATED);
    }
    
    
    
    private final void importVersionContentAndMetadata(final NodeRef                nodeRef,
                                                       final BulkImportItem.Version version,
                                                       final boolean                dryRun)
        throws InterruptedException
    {
        if (version.hasMetadata())
        {
            importVersionMetadata(nodeRef, version, dryRun);
        }
        
        if (version.hasContent())
        {
            importVersionContent(nodeRef, version, dryRun);
        }
    }
    
    
    private final void importVersionMetadata(final NodeRef                nodeRef,
                                             final BulkImportItem.Version version,
                                             final boolean                dryRun)
        throws InterruptedException
    {
        String                    type     = version.getType();
        Set<String>               aspects  = version.getAspects();
        Map<String, Serializable> metadata = version.getMetadata();
        
        if (type != null)
        {
            if (dryRun)
            {
                if (log.isInfoEnabled()) log.info("[DRY RUN] Would have set type of '" + String.valueOf(nodeRef) + "' to '" + String.valueOf(type) + "'.");
            }
            else
            {
                if (log.isDebugEnabled()) log.debug("Setting type of '" + String.valueOf(nodeRef) + "' to '" + String.valueOf(type) + "'.");
                nodeService.setType(nodeRef, QName.createQName(type));
            }
        }
        
        if (aspects != null)
        {
            for (final String aspect : aspects)
            {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException(Thread.currentThread().getName() + " was interrupted. Terminating early.");

                if (dryRun)
                {
                    if (log.isInfoEnabled()) log.info("[DRY RUN] Would have added aspect '" + aspect + "' to '" + String.valueOf(nodeRef) + "'.");
                }
                else
                {
                    if (log.isDebugEnabled()) log.debug("Adding aspect '" + aspect + "' to '" + String.valueOf(nodeRef) + "'.");
                    nodeService.addAspect(nodeRef, QName.createQName(aspect), null);
                }
            }
            
            importStatus.incrementTargetCounter(COUNTER_ASPECTS_ASSOCIATED, aspects.size());
        }
        
        if (version.hasMetadata() && metadata != null)
        {
            // QName all the keys.  It's baffling that NodeService doesn't have a method that accepts a Map<String, Serializable>, when things like VersionService do...
            Map<QName, Serializable> qNamedMetadata = new HashMap<QName, Serializable>(metadata.size());
            
            for (final String key : metadata.keySet())
            {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException(Thread.currentThread().getName() + " was interrupted. Terminating early.");
                
                QName        keyQName = QName.createQName(key);
                Serializable value    = metadata.get(key);
                
                qNamedMetadata.put(keyQName, value);
            }

            if (dryRun)
            {
                if (log.isInfoEnabled()) log.info("[DRY RUN] Would have added the following properties to '" + String.valueOf(nodeRef) + "':\n" + String.valueOf(qNamedMetadata));
            }
            else
            {
                try
                {
                    if (log.isDebugEnabled()) log.debug("Adding the following properties to '" + String.valueOf(nodeRef) + "':\n" + String.valueOf(qNamedMetadata));
                    nodeService.addProperties(nodeRef, qNamedMetadata);
                }
                catch (final InvalidNodeRefException inre)
                {
                    if (!nodeRef.equals(inre.getNodeRef()))
                    {
                        // Caused by an invalid NodeRef in the metadata (e.g. in an association)
                        throw new IllegalStateException("Invalid nodeRef found in metadata for '" + version.getMetadataSource() + "'.  " +
                                                        "Probable cause: an association is being populated via metadata, but the " +
                                                        "NodeRef for the target of that association ('" + inre.getNodeRef() + "') is invalid.  " +
                                                        "Please double check your metadata file and try again.", inre);
                    }
                    else
                    {
                        // Logic bug in the BFSIT.  :-(
                        throw inre;
                    }
                }
            }
            
            importStatus.incrementTargetCounter(COUNTER_PROPERTIES_POPULATED, metadata.size());
        }
    }
    

    private final void importVersionContent(final NodeRef                nodeRef,
                                            final BulkImportItem.Version version,
                                            final boolean                dryRun)
        throws InterruptedException
    {
        if (version.hasContent())
        {
            if (version.contentIsInPlace())
            {
                if (dryRun)
                {
                    if (log.isInfoEnabled()) log.info("[DRY RUN] Content for node '" + String.valueOf(nodeRef) + "' is in-place.");
                }
                else
                {
                    if (log.isDebugEnabled()) log.debug("Content for node '" + String.valueOf(nodeRef) + "' is in-place.");
                }
                
                if (!version.hasMetadata() ||
                    version.getMetadata() == null ||
                    (!version.getMetadata().containsKey(ContentModel.PROP_CONTENT.toPrefixString()) &&
                     !version.getMetadata().containsKey(ContentModel.PROP_CONTENT.toString())))
                {
                    log.warn("Source system is reporting that content is in place for '" + version.getContentSource() +
                             "', but metadata doesn't contain the '" + String.valueOf(ContentModel.PROP_CONTENT) +
                             "' property.  It is likely that the source system you selected is improperly implemented, with the result that you will have invalid content in your repository.");
                }
                
                importStatus.incrementTargetCounter(COUNTER_IN_PLACE_CONTENT_LINKED);
            }
            else  // Content needs to be streamed into the repository
            {
                if (dryRun)
                {
                    if (log.isInfoEnabled()) log.info("[DRY RUN] Would have streamed content from '" + version.getContentSource() + "' into node '" + String.valueOf(nodeRef) + "'.");
                }
                else
                {
                    if (log.isDebugEnabled()) log.debug("Streaming content from '" + version.getContentSource() + "' into node '" + String.valueOf(nodeRef) + "'.");
                    
                    ContentWriter writer = contentService.getWriter(nodeRef, ContentModel.PROP_CONTENT, true);
                    version.putContent(writer);
                    importStatus.incrementTargetCounter(COUNTER_CONTENT_STREAMED_BYTES, writer.getSize());
                }
                
                importStatus.incrementTargetCounter(COUNTER_CONTENT_STREAMED);
            }
        }
    }
    
}
