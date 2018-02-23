/*
 * Copyright 2016 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.api.internal.changedetection.state;

import org.gradle.internal.file.FileType;
import org.gradle.internal.hash.HashCode;

public class FileHashSnapshot extends AbstractFileContentSnapshot {
    public FileHashSnapshot(HashCode hash) {
        this(hash, 0L);
    }

    public FileHashSnapshot(HashCode hash, long lastModified) {
        super(hash, lastModified);
    }

    @Override
    public FileType getType() {
        return FileType.RegularFile;
    }

}
