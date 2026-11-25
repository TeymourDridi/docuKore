package com.docukore.infrastructure.bootstrap;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Fail-closed owner-only secret-file access on POSIX and Windows filesystems. */
final class PrivateFiles
{
    /** No permissions for group or other users. */
    private static final Set<PosixFilePermission> OWNER_ONLY = PosixFilePermissions.fromString("rw-------");

    /** Utility class. */
    private PrivateFiles()
    {
    }

    /**
     * Check an existing regular private file owned by the invoking OS user.
     *
     * @param path absolute file path
     * @throws IOException when file metadata is unavailable
     */
    static void requirePrivate(Path path) throws IOException
    {
        requireParent(path);
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path))
        {
            throw new BootstrapFailure("BOOTSTRAP_FILE_UNSAFE");
        }
        UserPrincipal current = currentUser(path);
        if (!Files.getOwner(path, LinkOption.NOFOLLOW_LINKS).equals(current))
        {
            throw new BootstrapFailure("BOOTSTRAP_FILE_OWNER_INVALID");
        }
        PosixFileAttributeView posix = Files.getFileAttributeView(
            path, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (posix != null)
        {
            Set<PosixFilePermission> permissions = posix.readAttributes().permissions();
            if (!OWNER_ONLY.containsAll(permissions) || !permissions.contains(PosixFilePermission.OWNER_READ))
            {
                throw new BootstrapFailure("BOOTSTRAP_FILE_PERMISSIONS_INVALID");
            }
            return;
        }
        AclFileAttributeView acl = Files.getFileAttributeView(
            path, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (acl == null)
        {
            throw new BootstrapFailure("BOOTSTRAP_FILE_PERMISSIONS_UNSUPPORTED");
        }
        for (AclEntry entry : acl.getAcl())
        {
            if (entry.type() == AclEntryType.ALLOW && !entry.principal().equals(current)
                && !entry.permissions().isEmpty())
            {
                throw new BootstrapFailure("BOOTSTRAP_FILE_PERMISSIONS_INVALID");
            }
        }
    }

    /**
     * Create a new file with owner-only access before writing any credentials.
     *
     * @param path absolute new file path; its private directory must already exist
     * @throws IOException when file creation fails
     */
    static void createPrivate(Path path) throws IOException
    {
        requireParent(path);
        if (Files.getFileAttributeView(path.getParent(), PosixFileAttributeView.class) != null)
        {
            Files.createFile(path, PosixFilePermissions.asFileAttribute(OWNER_ONLY));
        } else
        {
            AclFileAttributeView parentAcl = Files.getFileAttributeView(path.getParent(), AclFileAttributeView.class);
            if (parentAcl == null)
            {
                throw new BootstrapFailure("BOOTSTRAP_FILE_PERMISSIONS_UNSUPPORTED");
            }
            Files.createFile(path);
            AclFileAttributeView acl = Files.getFileAttributeView(path, AclFileAttributeView.class);
            UserPrincipal current = currentUser(path);
            acl.setOwner(current);
            acl.setAcl(List.of(AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(current)
                .setPermissions(EnumSet.allOf(AclEntryPermission.class)).build()));
        }
        requirePrivate(path);
    }

    /**
     * Write only to an existing, verified private file, refusing symbolic links.
     *
     * @param path output path
     * @param content secret content; never logged
     * @throws IOException when writing fails
     */
    static void write(Path path, String content) throws IOException
    {
        requirePrivate(path);
        Path temporary = path.resolveSibling(".bootstrap-" + UUID.randomUUID() + ".tmp");
        createPrivate(temporary);
        try
        {
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS))
            {
                ByteBuffer bytes = StandardCharsets.UTF_8.encode(content);
                while (bytes.hasRemaining())
                {
                    channel.write(bytes);
                }
                channel.force(true);
            }
            requirePrivate(path);
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally
        {
            Files.deleteIfExists(temporary);
        }
    }

    /**
     * Require an existing nonsymlink directory; bootstrap never creates arbitrary directories.
     *
     * @param path target file path
     * @throws IOException when canonicalization fails
     */
    private static void requireParent(Path path) throws IOException
    {
        if (!path.isAbsolute() || path.getParent() == null || !Files.isDirectory(path.getParent())
            || !path.getParent().toRealPath().equals(path.getParent()))
        {
            throw new BootstrapFailure("BOOTSTRAP_FILE_PATH_INVALID");
        }
    }

    /**
     * Resolve the current OS identity without accepting a configured owner name.
     *
     * @param path filesystem to query
     * @return current user principal
     * @throws IOException when user lookup fails
     */
    private static UserPrincipal currentUser(Path path) throws IOException
    {
        return path.getFileSystem().getUserPrincipalLookupService()
            .lookupPrincipalByName(System.getProperty("user.name"));
    }
}
