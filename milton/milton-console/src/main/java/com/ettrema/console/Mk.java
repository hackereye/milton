/*
* Licensed to the Apache Software Foundation (ASF) under one
* or more contributor license agreements.  See the NOTICE file
* distributed with this work for additional information
* regarding copyright ownership.  The ASF licenses this file
* to you under the Apache License, Version 2.0 (the
* "License"); you may not use this file except in compliance
* with the License.  You may obtain a copy of the License at
*
* http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing,
* software distributed under the License is distributed on an
* "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
* KIND, either express or implied.  See the License for the
* specific language governing permissions and limitations
* under the License.
*/


package com.ettrema.console;

import com.bradmcevoy.common.Path;
import com.bradmcevoy.http.CollectionResource;
import com.bradmcevoy.http.PutableResource;
import com.bradmcevoy.http.exceptions.BadRequestException;
import com.bradmcevoy.http.exceptions.ConflictException;
import com.bradmcevoy.http.exceptions.NotAuthorizedException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.slf4j.LoggerFactory;

public class Mk extends AbstractConsoleCommand {

    private static final org.slf4j.Logger log = LoggerFactory.getLogger( Mk.class );
    
    public Mk(List<String> args, String host, String currentDir, ConsoleResourceFactory resourceFactory) {
        super(args, host, currentDir, resourceFactory);
    }

    @Override
    public Result execute() {
        try {
            String newName = args.get(0);
            if( newName == null || newName.length() == 0 ) {
                return result("Please enter a new file name");
            }
            String content = "";
            if( args.size() > 1 ) {
                content = args.get(1);
            }
            ByteArrayInputStream inputStream;
            try {
                inputStream = new ByteArrayInputStream( content.getBytes("UTF-8"));
            } catch (UnsupportedEncodingException ex) {
                throw new RuntimeException(ex);
            }

            if( !cursor.isFolder() ) {
                return result("Couldnt find current folder");
            }
            CollectionResource cur = (CollectionResource) cursor.getResource();
            if( cur.child(newName) != null ) return result("File already exists: " + newName);

            if( cur instanceof PutableResource ) {
                PutableResource putable = (PutableResource) cur;
                try {
                    putable.createNew( newName, inputStream, (long) content.length(), newName );
                    Path newPath = cursor.getPath().child( newName );
                    return result( "created <a href='" + newPath + "'>" + newName + "</a>");
                } catch(BadRequestException e) {
                    return result("bad request exception");
                } catch(NotAuthorizedException ex) {
                    return result("not authorised");
                } catch( ConflictException ex ) {
                    return result("ConflictException writing content");
                } catch( IOException ex ) {
                    return result("IOException writing content");
                }
            } else {
                return result("the folder doesnt support creating new items");
            }
        } catch (NotAuthorizedException ex) {
            log.error("not authorised", ex);
            return result(ex.getLocalizedMessage());
        } catch (BadRequestException ex) {
            log.error("bad req", ex);
            return result(ex.getLocalizedMessage());
        }
    }

}
