package com.intridea.io.vfs.samples;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Properties;

import org.apache.commons.vfs2.FileObject;
import org.apache.commons.vfs2.FileSystemException;
import org.apache.commons.vfs2.FileSystemManager;
import org.apache.commons.vfs2.FileSystemOptions;
import org.apache.commons.vfs2.FileType;
import org.apache.commons.vfs2.Selectors;
import org.apache.commons.vfs2.VFS;
import org.apache.commons.vfs2.auth.StaticUserAuthenticator;
import org.apache.commons.vfs2.impl.DefaultFileSystemConfigBuilder;

import com.intridea.io.vfs.provider.s3.S3FileProvider;

public class S3Shell {

    private FileSystemManager fsManager;

    public S3Shell () throws FileNotFoundException, IOException {
        Properties config = new Properties();
        config.load(new FileInputStream("config.properties"));

        StaticUserAuthenticator auth = new StaticUserAuthenticator(null, config.getProperty("aws.key-id"), config.getProperty("aws.key"));
        FileSystemOptions opts = S3FileProvider.getDefaultFileSystemOptions();
        DefaultFileSystemConfigBuilder.getInstance().setUserAuthenticator(opts, auth);

        fsManager = VFS.getManager();

    }

    public void ls (String path) {
        try {
            FileObject file = fsManager.resolveFile(path);
            FileObject[] files = file.findFiles(Selectors.EXCLUDE_SELF);

            if (files.length > 0) {
                SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
                for (FileObject _file : files) {
                    String lastModDate = df.format(new Date(_file.getContent().getLastModifiedTime()));
                    long size = 0;
                    if (_file.getType() == FileType.FILE) {
                        size = _file.getContent().getSize();
                    }
                    String fullPath = _file.getName().getURI();
                    System.out.println(String.format("%-20s%-10s%s", lastModDate, size, fullPath));
                }
            }

        } catch (FileSystemException e) {
            System.err.println(e.getMessage());
        }
    }


    public static void main(String[] args) {
        S3Shell shell = null;
        try {
            shell = new S3Shell();
        } catch (Exception e) {
            System.err.println(String.format("Fail to init s3 shell. Reason: %s", e.getMessage()));
            e.printStackTrace();
            System.exit(1);
        }

            shell.ls("s3://disk-aaa/");
    }
}
