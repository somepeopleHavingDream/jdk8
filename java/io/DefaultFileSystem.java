/*
 * Copyright (c) 2012, Oracle and/or its affiliates. All rights reserved.
 * ORACLE PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 */

package java.io;

/**
 *
 * @since 1.8
 */
class DefaultFileSystem {

    /**
     * Return the FileSystem object for Unix-based platform.
     *
     * 返回用于基于Unix平台的文件系统对象
     */
    public static FileSystem getFileSystem() {
        // 说明默认的文件系统是Unix文件系统
        return new UnixFileSystem();
    }
}
