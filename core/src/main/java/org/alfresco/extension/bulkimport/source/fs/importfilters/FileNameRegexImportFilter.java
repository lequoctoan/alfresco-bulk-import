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

package org.alfresco.extension.bulkimport.source.fs.importfilters;


import java.util.regex.Pattern;

import org.alfresco.extension.bulkimport.source.BulkImportItem;
import org.alfresco.extension.bulkimport.source.fs.ImportFilter;


/**
 * This class is an <code>ImportFilter</code> that filters out files and/or folders whose name, excluding
 * path, matches the configured regular expression. 
 *
 * @author Peter Monks (pmonks@gmail.com)
 */
public class FileNameRegexImportFilter
    implements ImportFilter
{
    private final Pattern pattern;
    
    
    /**
     * Simple constructor for a FileNameRegexSourceFilter
     * 
     * @param filenameRegex The regex to use to match against file and folder names <i>(must not be null)</i>.
     */
    public FileNameRegexImportFilter(final String filenameRegex)
    {
        this.pattern = Pattern.compile(filenameRegex);
    }
    
    
    /**
     * @see org.alfresco.extension.bulkimport.source.fs.ImportFilter#shouldFilter(org.alfresco.extension.bulkimport.source.BulkImportItem)
     */
    public boolean shouldFilter(final BulkImportItem item)
    {
        return(pattern.matcher(item.getName()).matches());
    }

}
