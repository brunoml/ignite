package org.apache.ignite.igfs;

import java.io.Externalizable;
import java.io.IOException;
import java.net.URI;

/**
 * This factory is {@link Externalizable} because it should be transferable over the network.
 *
 * @param <T> The type
 */
public interface HadoopFileSystemFactory <T> extends Externalizable {
    /**
     * Gets the file system, possibly creating it or taking a cached instance.
     * All the other data needed for the file system creation are expected to be contained
     * in this object instance.
     *
     * @param userName The user name
     * @return The file system.
     * @throws IOException On error.
     */
    public T get(String userName) throws IOException;

    /**
     * Getter for the file system URI.
     *
     * @return The file system URI.
     */
    public URI uri();
}
