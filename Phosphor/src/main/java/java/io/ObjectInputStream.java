/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package java.io;

import edu.columbia.cs.psl.phosphor.runtime.Taint;
import edu.columbia.cs.psl.phosphor.control.ControlFlowStack;
import edu.columbia.cs.psl.phosphor.struct.TaintedReferenceWithObjTag;

import java.lang.reflect.Array;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;

/**
 * A specialized {@link InputStream} that is able to read (deserialize) Java
 * objects as well as primitive data types (int, byte, char etc.). The data has
 * typically been saved using an ObjectOutputStream.
 *
 * @see ObjectOutputStream
 * @see ObjectInput
 * @see Serializable
 * @see Externalizable
 */
public class ObjectInputStream extends InputStream implements ObjectInput,
        ObjectStreamConstants {

    private InputStream emptyStream = new ByteArrayInputStream(
            new byte[0]);

    // To put into objectsRead when reading unsharedObject
    private static final Object UNSHARED_OBJ = new Object(); // $NON-LOCK-1$

    // If the receiver has already read & not consumed a TC code
    private boolean hasPushbackTC;

    // Push back TC code if the variable above is true
    private byte pushbackTC;

    // How many nested levels to readObject. When we reach 0 we have to validate
    // the graph then reset it
    private int nestedLevels;

    // All objects are assigned an ID (integer handle)
    private int currentHandle;

    // Where we read from
    private DataInputStream input;

    // Where we read primitive types from
    private DataInputStream primitiveTypes;

    // Where we keep primitive type data
    private InputStream primitiveData = emptyStream;

    // Resolve object is a mechanism for replacement
    private boolean enableResolve;

    // Table mapping Integer (handle) -> Object
    private HashMap<Integer, Object> objectsRead;

    // Used by defaultReadObject
    private Object currentObject;

    // Used by defaultReadObject
    private ObjectStreamClass currentClass;

    // All validations to be executed when the complete graph is read. See inner
    // type below.
    private InputValidationDesc[] validations;

    // Allows the receiver to decide if it needs to call readObjectOverride
    private boolean subclassOverridingImplementation;

    // Original caller's class loader, used to perform class lookups
    private ClassLoader callerClassLoader;

    // false when reading missing fields
    private boolean mustResolve = true;

    // Handle for the current class descriptor
    private Integer descriptorHandle;

    private static final HashMap<String, Class<?>> PRIMITIVE_CLASSES =
            new HashMap<String, Class<?>>();

    static {
        PRIMITIVE_CLASSES.put("byte", byte.class); //$NON-NLS-1$
        PRIMITIVE_CLASSES.put("short", short.class); //$NON-NLS-1$
        PRIMITIVE_CLASSES.put("int", int.class); //$NON-NLS-1$
        PRIMITIVE_CLASSES.put("long", long.class); //$NON-NLS-1$
        PRIMITIVE_CLASSES.put("boolean", boolean.class); //$NON-NLS-1$
        PRIMITIVE_CLASSES.put("char", char.class); //$NON-NLS-1$
        PRIMITIVE_CLASSES.put("float", float.class); //$NON-NLS-1$
        PRIMITIVE_CLASSES.put("double", double.class); //$NON-NLS-1$
        PRIMITIVE_CLASSES.put("void", void.class); //$NON-NLS-1$
    }

    // Internal type used to keep track of validators & corresponding priority
    static class InputValidationDesc {
        ObjectInputValidation validator;

        int priority;
    }

    /**
     * GetField is an inner class that provides access to the persistent fields
     * read from the source stream.
     */
    public abstract static class GetField {
        /**
         * Gets the ObjectStreamClass that describes a field.
         *
         * @return the descriptor class for a serialized field.
         */
        public abstract ObjectStreamClass getObjectStreamClass();

        /**
         * Indicates if the field identified by {@code name} is defaulted. This
         * means that it has no value in this stream.
         *
         * @param name
         *            the name of the field to check.
         * @return {@code true} if the field is defaulted, {@code false}
         *         otherwise.
         * @throws IllegalArgumentException
         *             if {@code name} does not identify a serializable field.
         * @throws IOException
         *             if an error occurs while reading from the source input
         *             stream.
         */
        public abstract boolean defaulted(String name) throws IOException,
                IllegalArgumentException;

        /**
         * Gets the value of the boolean field identified by {@code name} from
         * the persistent field.
         *
         * @param name
         *            the name of the field to get.
         * @param defaultValue
         *            the default value that is used if the field does not have
         *            a value when read from the source stream.
         * @return the value of the field identified by {@code name}.
         * @throws IOException
         *             if an error occurs while reading from the source input
         *             stream.
         * @throws IllegalArgumentException
         *             if the type of the field identified by {@code name} is
         *             not {@code boolean}.
         */
        public abstract boolean get(String name, boolean defaultValue)
                throws IOException, IllegalArgumentException;

        /**
         * Gets the value of the character field identified by {@code name} from
         * the persistent field.
         *
         * @param name
         *            the name of the field to get.
         * @param defaultValue
         *            the default value that is used if the field does not have
         *            a value when read from the source stream.
         * @return the value of the field identified by {@code name}.
         * @throws IOException
         *             if an error occurs while reading from the source input
         *             stream.
         * @throws IllegalArgumentException
         *             if the type of the field identified by {@code name} is
         *             not {@code char}.
         */
        public abstract char get(String name, char defaultValue)
                throws IOException, IllegalArgumentException;

        /**
         * Gets the value of the byte field identified by {@code name} from the
         * persistent field.
         *
         * @param name
         *            the name of the field to get.
         * @param defaultValue
         *            the default value that is used if the field does not have
         *            a value when read from the source stream.
         * @return the value of the field identified by {@code name}.
         * @throws IOException
         *             if an error occurs while reading from the source input
         *             stream.
         * @throws IllegalArgumentException
         *             if the type of the field identified by {@code name} is
         *             not {@code byte}.
         */
        public abstract byte get(String name, byte defaultValue)
                throws IOException, IllegalArgumentException;

        /**
         * Gets the value of the short field identified by {@code name} from the
         * persistent field.
         *
         * @param name
         *            the name of the field to get.
         * @param defaultValue
         *            the default value that is used if the field does not have
         *            a value when read from the source stream.
         * @return the value of the field identified by {@code name}.
         * @throws IOException
         *             if an error occurs while reading from the source input
         *             stream.
         * @throws IllegalArgumentException
         *             if the type of the field identified by {@code name} is
         *             not {@code short}.
         */
        public abstract short get(String name, short defaultValue)
                throws IOException, IllegalArgumentException;

        /**
         * Gets the value of the integer field identified by {@code name} from
         * the persistent field.
         *
         * @param name
         *            the name of the field to get.
         * @param defaultValue
         *            the default value that is used if the field does not have
         *            a value when read from the source stream.
         * @return the value of the field identified by {@code name}.
         * @throws IOException
         *             if an error occurs while reading from the source input
         *             stream.
         * @throws IllegalArgumentException
         *             if the type of the field identified by {@code name} is
         *             not {@code int}.
         */
        public abstract int get(String name, int defaultValue)
                throws IOException, IllegalArgumentException;

        /**
         * Gets the value of the long field identified by {@code name} from the
         * persistent field.
         *
         * @param name
         *            the name of the field to get.
         * @param defaultValue
         *            the default value that is used if the field does not have
         *            a value when read from the source stream.
         * @return the value of the field identified by {@code name}.
         * @throws IOException
         *             if an error occurs while reading from the source input
         *             stream.
         * @throws IllegalArgumentException
         *             if the type of the field identified by {@code name} is
         *             not {@code long}.
         */
        public abstract long get(String name, long defaultValue)
                throws IOException, IllegalArgumentException;

        /**
         * Gets the value of the float field identified by {@code name} from the
         * persistent field.
         *
         * @param name
         *            the name of the field to get.
         * @param defaultValue
         *            the default value that is used if the field does not have
         *            a value when read from the source stream.
         * @return the value of the field identified by {@code name}.
         * @throws IOException
         *             if an error occurs while reading from the source input
         *             stream.
         * @throws IllegalArgumentException
         *             if the type of the field identified by {@code float} is
         *             not {@code char}.
         */
        public abstract float get(String name, float defaultValue)
                throws IOException, IllegalArgumentException;

        /**
         * Gets the value of the double field identified by {@code name} from
         * the persistent field.
         *
         * @param name
         *            the name of the field to get.
         * @param defaultValue
         *            the default value that is used if the field does not have
         *            a value when read from the source stream.
         * @return the value of the field identified by {@code name}.
         * @throws IOException
         *             if an error occurs while reading from the source input
         *             stream.
         * @throws IllegalArgumentException
         *             if the type of the field identified by {@code name} is
         *             not {@code double}.
         */
        public abstract double get(String name, double defaultValue)
                throws IOException, IllegalArgumentException;

        /**
         * Gets the value of the object field identified by {@code name} from
         * the persistent field.
         *
         * @param name
         *            the name of the field to get.
         * @param defaultValue
         *            the default value that is used if the field does not have
         *            a value when read from the source stream.
         * @return the value of the field identified by {@code name}.
         * @throws IOException
         *             if an error occurs while reading from the source input
         *             stream.
         * @throws IllegalArgumentException
         *             if the type of the field identified by {@code name} is
         *             not {@code Object}.
         */
        public abstract Object get(String name, Object defaultValue)
                throws IOException, IllegalArgumentException;
    }

    /**
     * Constructs a new ObjectInputStream. This default constructor can be used
     * by subclasses that do not want to use the public constructor if it
     * allocates unneeded data.
     *
     * @throws IOException
     *             if an error occurs when creating this stream.
     * @throws SecurityException
     *             if a security manager is installed and it denies subclassing
     *             this class.
     * @see SecurityManager#checkPermission(java.security.Permission)
     */
    protected ObjectInputStream() throws IOException, SecurityException {
        super();
        SecurityManager currentManager = System.getSecurityManager();
        if (currentManager != null) {
            currentManager.checkPermission(SUBCLASS_IMPLEMENTATION_PERMISSION);
        }
        // WARNING - we should throw IOException if not called from a subclass
        // according to the JavaDoc. Add the test.
        this.subclassOverridingImplementation = true;
    }

    /**
     * Constructs a new ObjectInputStream that reads from the InputStream
     * {@code input}.
     *
     * @param input
     *            the non-null source InputStream to filter reads on.
     * @throws IOException
     *             if an error occurs while reading the stream header.
     * @throws StreamCorruptedException
     *             if the source stream does not contain serialized objects that
     *             can be read.
     * @throws SecurityException
     *             if a security manager is installed and it denies subclassing
     *             this class.
     */
    public ObjectInputStream(InputStream input)
            throws StreamCorruptedException, IOException {
        final Class<?> implementationClass = getClass();
        final Class<?> thisClass = ObjectInputStream.class;
        SecurityManager sm = System.getSecurityManager();
        if (sm != null && implementationClass != thisClass) {
            boolean mustCheck = false;
            if (mustCheck) {
                sm
                        .checkPermission(ObjectStreamConstants.SUBCLASS_IMPLEMENTATION_PERMISSION);
            }
        }
        this.input = (input instanceof DataInputStream) ? (DataInputStream) input
                : new DataInputStream(input);
        primitiveTypes = new DataInputStream(this);
        enableResolve = false;
        this.subclassOverridingImplementation = false;
        resetState();
        nestedLevels = 0;
        // So read...() methods can be used by
        // subclasses during readStreamHeader()
        primitiveData = this.input;
        // Has to be done here according to the specification
        readStreamHeader();
        primitiveData = emptyStream;
    }

    /**
     * Returns the number of bytes of primitive data that can be read from this
     * stream without blocking. This method should not be used at any arbitrary
     * position; just when reading primitive data types (int, char etc).
     *
     * @return the number of available primitive data bytes.
     * @throws IOException
     *             if any I/O problem occurs while computing the available
     *             bytes.
     */
    @Override
    public int available() throws IOException {
        // returns 0 if next data is an object, or N if reading primitive types
        checkReadPrimitiveTypes();
        return primitiveData.available();
    }

    /**
     * Checks to if it is ok to read primitive types from this stream at
     * this point. One is not supposed to read primitive types when about to
     * read an object, for example, so an exception has to be thrown.
     *
     * @throws IOException
     *             If any IO problem occurred when trying to read primitive type
     *             or if it is illegal to read primitive types
     */
    private void checkReadPrimitiveTypes() throws IOException {
        // If we still have primitive data, it is ok to read primitive data
        if (primitiveData == input || primitiveData.available() > 0) {
            return;
        }

        // If we got here either we had no Stream previously created or
        // we no longer have data in that one, so get more bytes
        do {
            int next = 0;
            if (hasPushbackTC) {
                hasPushbackTC = false;
            } else {
                next = input.read();
                pushbackTC = (byte) next;
            }
            switch (pushbackTC) {
                case TC_BLOCKDATA:
                    primitiveData = new ByteArrayInputStream(readBlockData());
                    return;
                case TC_BLOCKDATALONG:
                    primitiveData = new ByteArrayInputStream(
                            readBlockDataLong());
                    return;
                case TC_RESET:
                    resetState();
                    break;
                default:
                    if (next != -1) {
                        pushbackTC();
                    }
                    return;
            }
            // Only TC_RESET falls through
        } while (true);
    }

    /**
     * Closes this stream. This implementation closes the source stream.
     *
     * @throws IOException
     *             if an error occurs while closing this stream.
     */
    @Override
    public void close() throws IOException {
        input.close();
    }

    /**
     * Default method to read objects from this stream. Serializable fields
     * defined in the object's class and superclasses are read from the source
     * stream.
     *
     * @throws ClassNotFoundException
     *             if the object's class cannot be found.
     * @throws IOException
     *             if an I/O error occurs while reading the object data.
     * @throws NotActiveException
     *             if this method is not called from {@code readObject()}.
     * @see ObjectOutputStream#defaultWriteObject
     */
    public void defaultReadObject() throws IOException, ClassNotFoundException,
            NotActiveException {
        // We can't be called from just anywhere. There are rules.
        if (currentObject != null || !mustResolve) {
        } else {
            throw new NotActiveException();
        }
    }

    /**
     * Enables object replacement for this stream. By default this is not
     * enabled. Only trusted subclasses (loaded with system class loader) are
     * allowed to change this status.
     *
     * @param enable
     *            {@code true} to enable object replacement; {@code false} to
     *            disable it.
     * @return the previous setting.
     * @throws SecurityException
     *             if a security manager is installed and it denies enabling
     *             object replacement for this stream.
     * @see #resolveObject
     * @see ObjectOutputStream#enableReplaceObject
     */
    protected boolean enableResolveObject(boolean enable)
            throws SecurityException {
        if (enable) {
            // The Stream has to be trusted for this feature to be enabled.
            // trusted means the stream's classloader has to be null
            SecurityManager currentManager = System.getSecurityManager();
            if (currentManager != null) {
                currentManager.checkPermission(SUBSTITUTION_PERMISSION);
            }
        }
        boolean originalValue = enableResolve;
        enableResolve = enable;
        return originalValue;
    }

    /**
     * Checks if two classes belong to the same package.
     *
     * @param c1
     *            one of the classes to test.
     * @param c2
     *            the other class to test.
     * @return {@code true} if the two classes belong to the same package,
     *         {@code false} otherwise.
     */
    private boolean inSamePackage(Class<?> c1, Class<?> c2) {
        String nameC1 = c1.getName();
        String nameC2 = c2.getName();
        int indexDotC1 = nameC1.lastIndexOf('.');
        int indexDotC2 = nameC2.lastIndexOf('.');
        if (indexDotC1 != indexDotC2) {
            return false; // cannot be in the same package if indices are not
        }
        // the same
        if (indexDotC1 < 0) {
            return true; // both of them are in default package
        }
        return nameC1.substring(0, indexDotC1).equals(
                nameC2.substring(0, indexDotC2));
    }

    /**
     * Return the next {@code int} handle to be used to indicate cyclic
     * references being loaded from the stream.
     *
     * @return the next handle to represent the next cyclic reference
     */
    private Integer nextHandle() {
        return Integer.valueOf(this.currentHandle++);
    }

    /**
     * Return the next token code (TC) from the receiver, which indicates what
     * kind of object follows
     *
     * @return the next TC from the receiver
     *
     * @throws IOException
     *             If an IO error occurs
     *
     * @see ObjectStreamConstants
     */
    private byte nextTC() throws IOException {
        if (hasPushbackTC) {
            hasPushbackTC = false; // We are consuming it
        } else {
            // Just in case a later call decides to really push it back,
            // we don't require the caller to pass it as parameter
            pushbackTC = input.readByte();
        }
        return pushbackTC;
    }

    /**
     * Pushes back the last TC code read
     */
    private void pushbackTC() {
        hasPushbackTC = true;
    }

    /**
     * Reads a single byte from the source stream and returns it as an integer
     * in the range from 0 to 255. Returns -1 if the end of the source stream
     * has been reached. Blocks if no input is available.
     *
     * @return the byte read or -1 if the end of the source stream has been
     *         reached.
     * @throws IOException
     *             if an error occurs while reading from this stream.
     */
    @Override
    public int read() throws IOException {
        checkReadPrimitiveTypes();
        return primitiveData.read();
    }

    /**
     * Reads at most {@code length} bytes from the source stream and stores them
     * in byte array {@code buffer} starting at offset {@code count}. Blocks
     * until {@code count} bytes have been read, the end of the source stream is
     * detected or an exception is thrown.
     *
     * @param buffer
     *            the array in which to store the bytes read.
     * @param offset
     *            the initial position in {@code buffer} to store the bytes
     *            read from the source stream.
     * @param length
     *            the maximum number of bytes to store in {@code buffer}.
     * @return the number of bytes read or -1 if the end of the source input
     *         stream has been reached.
     * @throws IndexOutOfBoundsException
     *             if {@code offset < 0} or {@code length < 0}, or if
     *             {@code offset + length} is greater than the length of
     *             {@code buffer}.
     * @throws IOException
     *             if an error occurs while reading from this stream.
     * @throws NullPointerException
     *             if {@code buffer} is {@code null}.
     */
    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        // Force buffer null check first!
        if (offset > buffer.length || offset < 0) {
            // luni.12=Offset out of bounds \: {0}
            throw new ArrayIndexOutOfBoundsException(Messages.getString("luni.12", offset)); //$NON-NLS-1$
        }
        if (length < 0 || length > buffer.length - offset) {
            // luni.18=Length out of bounds \: {0}
            throw new ArrayIndexOutOfBoundsException(Messages.getString("luni.18", length)); //$NON-NLS-1$
        }
        if (length == 0) {
            return 0;
        }
        checkReadPrimitiveTypes();
        return primitiveData.read(buffer, offset, length);
    }

    /**
     * Reads and returns an array of raw bytes with primitive data. The array
     * will have up to 255 bytes. The primitive data will be in the format
     * described by {@code DataOutputStream}.
     *
     * @return The primitive data read, as raw bytes
     *
     * @throws IOException
     *             If an IO exception happened when reading the primitive data.
     */
    private byte[] readBlockData() throws IOException {
        byte[] result = new byte[input.readByte() & 0xff];
        input.readFully(result);
        return result;
    }

    /**
     * Reads and returns an array of raw bytes with primitive data. The array
     * will have more than 255 bytes. The primitive data will be in the format
     * described by {@code DataOutputStream}.
     *
     * @return The primitive data read, as raw bytes
     *
     * @throws IOException
     *             If an IO exception happened when reading the primitive data.
     */
    private byte[] readBlockDataLong() throws IOException {
        byte[] result = new byte[input.readInt()];
        input.readFully(result);
        return result;
    }

    /**
     * Reads a boolean from the source stream.
     *
     * @return the boolean value read from the source stream.
     * @throws EOFException
     *             if the end of the input is reached before the read
     *             request can be satisfied.
     * @throws IOException
     *             if an error occurs while reading from the source stream.
     */
    public boolean readBoolean() throws IOException {
        return primitiveTypes.readBoolean();
    }

    /**
     * Reads a byte (8 bit) from the source stream.
     *
     * @return the byte value read from the source stream.
     * @throws EOFException
     *             if the end of the input is reached before the read
     *             request can be satisfied.
     * @throws IOException
     *             if an error occurs while reading from the source stream.
     */
    public byte readByte() throws IOException {
        return primitiveTypes.readByte();
    }

    /**
     * Reads a character (16 bit) from the source stream.
     *
     * @return the char value read from the source stream.
     * @throws EOFException
     *             if the end of the input is reached before the read
     *             request can be satisfied.
     * @throws IOException
     *             if an error occurs while reading from the source stream.
     */
    public char readChar() throws IOException {
        return primitiveTypes.readChar();
    }

    /**
     * Reads and discards block data and objects until TC_ENDBLOCKDATA is found.
     *
     * @throws IOException
     *             If an IO exception happened when reading the optional class
     *             annotation.
     * @throws ClassNotFoundException
     *             If the class corresponding to the class descriptor could not
     *             be found.
     */
    private void discardData() throws ClassNotFoundException, IOException {
        primitiveData = emptyStream;
        boolean resolve = mustResolve;
        mustResolve = false;
        do {
            byte tc = nextTC();
            if (tc == TC_ENDBLOCKDATA) {
                mustResolve = resolve;
                return; // End of annotation
            }
            readContent(tc);
        } while (true);
    }

    /**
     * Reads a class descriptor (an {@code ObjectStreamClass}) from the
     * stream.
     *
     * @return the class descriptor read from the stream
     *
     * @throws IOException
     *             If an IO exception happened when reading the class
     *             descriptor.
     * @throws ClassNotFoundException
     *             If the class corresponding to the class descriptor could not
     *             be found.
     */
    private ObjectStreamClass readClassDesc() throws ClassNotFoundException,
            IOException {
        byte tc = nextTC();
        switch (tc) {
            case TC_CLASSDESC:
                return readNewClassDesc(false);
            case TC_PROXYCLASSDESC:
                Class<?> proxyClass = readNewProxyClassDesc();
                ObjectStreamClass streamClass = ObjectStreamClass
                        .lookup(proxyClass);
                registerObjectRead(streamClass, nextHandle(), false);
                checkedSetSuperClassDesc(streamClass, readClassDesc());
                return streamClass;
            case TC_REFERENCE:
                return (ObjectStreamClass) readCyclicReference();
            case TC_NULL:
                return null;
            default:
                throw new StreamCorruptedException(Messages.getString(
                        "luni.BC", Integer.toHexString(tc & 0xff))); //$NON-NLS-1$
        }
    }

    private static class Messages {
        static String getString(String s1, Object... s2) {
            return null;
        }
    }

    /**
     * Reads the content of the receiver based on the previously read token
     * {@code tc}.
     *
     * @param tc
     *            The token code for the next item in the stream
     * @return the object read from the stream
     *
     * @throws IOException
     *             If an IO exception happened when reading the class
     *             descriptor.
     * @throws ClassNotFoundException
     *             If the class corresponding to the object being read could not
     *             be found.
     */
    private Object readContent(byte tc) throws ClassNotFoundException,
            IOException {
        switch (tc) {
            case TC_BLOCKDATA:
                return readBlockData();
            case TC_BLOCKDATALONG:
                return readBlockDataLong();
            case TC_CLASS:
                return readNewClass(false);
            case TC_CLASSDESC:
                return readNewClassDesc(false);
            case TC_ARRAY:
                return readNewArray(false);
            case TC_OBJECT:
                return readNewObject(false);
            case TC_STRING:
                return readNewString(false);
            case TC_LONGSTRING:
                return readNewLongString(false);
            case TC_REFERENCE:
                return readCyclicReference();
            case TC_NULL:
                return null;
            case TC_EXCEPTION:
                Exception exc = readException();
                throw new WriteAbortedException(Messages.getString("luni.BD"), exc); //$NON-NLS-1$
            case TC_RESET:
                resetState();
                return null;
            default:
                throw new StreamCorruptedException(Messages.getString(
                        "luni.BC", Integer.toHexString(tc & 0xff))); //$NON-NLS-1$
        }
    }

    /**
     * Reads the content of the receiver based on the previously read token
     * {@code tc}. Primitive data content is considered an error.
     *
     * @param unshared
     *            read the object unshared
     * @return the object read from the stream
     *
     * @throws IOException
     *             If an IO exception happened when reading the class
     *             descriptor.
     * @throws ClassNotFoundException
     *             If the class corresponding to the object being read could not
     *             be found.
     */
    private Object readNonPrimitiveContent(boolean unshared)
            throws ClassNotFoundException, IOException {
        return null;
    }

    /**
     * Reads the next item from the stream assuming it is a cyclic reference to
     * an object previously read. Return the actual object previously read.
     *
     * @return the object previously read from the stream
     *
     * @throws IOException
     *             If an IO exception happened when reading the class
     *             descriptor.
     * @throws InvalidObjectException
     *             If the cyclic reference is not valid.
     */
    private Object readCyclicReference() throws InvalidObjectException,
            IOException {
        return registeredObjectRead(readNewHandle());
    }

    /**
     * Reads a double (64 bit) from the source stream.
     *
     * @return the double value read from the source stream.
     * @throws EOFException
     *             if the end of the input is reached before the read
     *             request can be satisfied.
     * @throws IOException
     *             if an error occurs while reading from the source stream.
     */
    public double readDouble() throws IOException {
        return primitiveTypes.readDouble();
    }

    /**
     * Read the next item assuming it is an exception. The exception is not a
     * regular instance in the object graph, but the exception instance that
     * happened (if any) when dumping the original object graph. The set of seen
     * objects will be reset just before and just after loading this exception
     * object.
     * <p>
     * When exceptions are found normally in the object graph, they are loaded
     * as a regular object, and not by this method. In that case, the set of
     * "known objects" is not reset.
     *
     * @return the exception read
     *
     * @throws IOException
     *             If an IO exception happened when reading the exception
     *             object.
     * @throws ClassNotFoundException
     *             If a class could not be found when reading the object graph
     *             for the exception
     * @throws OptionalDataException
     *             If optional data could not be found when reading the
     *             exception graph
     * @throws WriteAbortedException
     *             If another exception was caused when dumping this exception
     */
    private Exception readException() throws WriteAbortedException,
            OptionalDataException, ClassNotFoundException, IOException {

        resetSeenObjects();

        // Now we read the Throwable object that was saved
        // WARNING - the grammar says it is a Throwable, but the
        // WriteAbortedException constructor takes an Exception. So, we read an
        // Exception from the stream
        Exception exc = (Exception) readObject();

        // We reset the receiver's state (the grammar has "reset" in normal
        // font)
        resetSeenObjects();
        return exc;
    }

    /**
     * Reads a collection of field descriptors (name, type name, etc) for the
     * class descriptor {@code cDesc} (an {@code ObjectStreamClass})
     *
     * @param cDesc
     *            The class descriptor (an {@code ObjectStreamClass})
     *            for which to write field information
     *
     * @throws IOException
     *             If an IO exception happened when reading the field
     *             descriptors.
     * @throws ClassNotFoundException
     *             If a class for one of the field types could not be found
     *
     * @see #readObject()
     */
    private void readFieldDescriptors(ObjectStreamClass cDesc)
            throws ClassNotFoundException, IOException {
    }

    /*
     * Format the class signature for ObjectStreamField, for example,
     * "[L[Ljava.lang.String;;" is converted to "[Ljava.lang.String;"
     */
    private static String formatClassSig(String classSig) {
        int start = 0;
        int end = classSig.length();

        if (end <= 0) {
            return classSig;
        }

        while (classSig.startsWith("[L", start) //$NON-NLS-1$
                && classSig.charAt(end - 1) == ';') {
            start += 2;
            end--;
        }

        if (start > 0) {
            start -= 2;
            end++;
            return classSig.substring(start, end);
        }
        return classSig;
    }

    /**
     * Reads the persistent fields of the object that is currently being read
     * from the source stream. The values read are stored in a GetField object
     * that provides access to the persistent fields. This GetField object is
     * then returned.
     *
     * @return the GetField object from which persistent fields can be accessed
     *         by name.
     * @throws ClassNotFoundException
     *             if the class of an object being deserialized can not be
     *             found.
     * @throws IOException
     *             if an error occurs while reading from this stream.
     * @throws NotActiveException
     *             if this stream is currently not reading an object.
     */
    public GetField readFields() throws IOException, ClassNotFoundException,
            NotActiveException {
        // We can't be called from just anywhere. There are rules.
        if (currentObject == null) {
            throw new NotActiveException();
        }
        return null;
    }

    private static Class<?> getFieldClass(final Object obj,
                                          final String fieldName) {
        return AccessController.doPrivileged(new PrivilegedAction<Class<?>>() {
            public Class<?> run() {
                Class<?> objClass = obj.getClass();
                while (objClass != null) {
                    try {
                        Class<?> fc =
                                objClass.getDeclaredField(fieldName).getType();
                        return fc;
                    } catch (NoSuchFieldException e) {
                        // Ignored
                    }
                    objClass = objClass.getSuperclass();
                }
                return null;
            }
        });
    }

    /**
     * Reads a float (32 bit) from the source stream.
     *
     * @return the float value read from the source stream.
     * @throws EOFException
     *             if the end of the input is reached before the read
     *             request can be satisfied.
     * @throws IOException
     *             if an error occurs while reading from the source stream.
     */
    public float readFloat() throws IOException {
        return primitiveTypes.readFloat();
    }

    /**
     * Reads bytes from the source stream into the byte array {@code buffer}.
     * This method will block until {@code buffer.length} bytes have been read.
     *
     * @param buffer
     *            the array in which to store the bytes read.
     * @throws EOFException
     *             if the end of the input is reached before the read
     *             request can be satisfied.
     * @throws IOException
     *             if an error occurs while reading from the source stream.
     */
    public void readFully(byte[] buffer) throws IOException {
        primitiveTypes.readFully(buffer);
    }

    /**
     * Reads bytes from the source stream into the byte array {@code buffer}.
     * This method will block until {@code length} number of bytes have been
     * read.
     *
     * @param buffer
     *            the byte array in which to store the bytes read.
     * @param offset
     *            the initial position in {@code buffer} to store the bytes
     *            read from the source stream.
     * @param length
     *            the maximum number of bytes to store in {@code buffer}.
     * @throws EOFException
     *             if the end of the input is reached before the read
     *             request can be satisfied.
     * @throws IOException
     *             if an error occurs while reading from the source stream.
     */
    public void readFully(byte[] buffer, int offset, int length)
            throws IOException {
        primitiveTypes.readFully(buffer, offset, length);
    }

    /**
     * Walks the hierarchy of classes described by class descriptor
     * {@code classDesc} and reads the field values corresponding to
     * fields declared by the corresponding class descriptor. The instance to
     * store field values into is {@code object}. If the class
     * (corresponding to class descriptor {@code classDesc}) defines
     * private instance method {@code readObject} it will be used to load
     * field values.
     *
     * @param object
     *            Instance into which stored field values loaded.
     * @param classDesc
     *            A class descriptor (an {@code ObjectStreamClass})
     *            defining which fields should be loaded.
     *
     * @throws IOException
     *             If an IO exception happened when reading the field values in
     *             the hierarchy.
     * @throws ClassNotFoundException
     *             If a class for one of the field types could not be found
     * @throws NotActiveException
     *             If {@code defaultReadObject} is called from the wrong
     *             context.
     *
     * @see #defaultReadObject
     * @see #readObject()
     */
    private void readHierarchy(Object object, ObjectStreamClass classDesc)
            throws IOException, ClassNotFoundException, NotActiveException {
        // We can't be called from just anywhere. There are rules.
        if (object == null && mustResolve) {
            throw new NotActiveException();
        }

        ArrayList<ObjectStreamClass> streamClassList = new ArrayList<ObjectStreamClass>(
                32);
        ObjectStreamClass nextStreamClass = classDesc;
        while (nextStreamClass != null) {
            streamClassList.add(0, nextStreamClass);
        }
        if (object == null) {
            Iterator<ObjectStreamClass> streamIt = streamClassList.iterator();
            while (streamIt.hasNext()) {
                ObjectStreamClass streamClass = streamIt.next();
                readObjectForClass(null, streamClass);
            }
        } else {
            ArrayList<Class<?>> classList = new ArrayList<Class<?>>(32);
            Class<?> nextClass = object.getClass();
            while (nextClass != null) {
                Class<?> testClass = nextClass.getSuperclass();
                if (testClass != null) {
                    classList.add(0, nextClass);
                }
                nextClass = testClass;
            }
            int lastIndex = 0;
            for (int i = 0; i < classList.size(); i++) {
                Class<?> superclass = classList.get(i);
                int index = findStreamSuperclass(superclass, streamClassList,
                        lastIndex);
                if (index == -1) {
                } else {
                    for (int j = lastIndex; j <= index; j++) {
                        readObjectForClass(object, streamClassList.get(j));
                    }
                    lastIndex = index + 1;
                }
            }
        }
    }

    private int findStreamSuperclass(Class<?> cl,
                                     ArrayList<ObjectStreamClass> classList, int lastIndex) {
        ObjectStreamClass objCl;
        String forName;

        for (int i = lastIndex; i < classList.size(); i++) {
            objCl = classList.get(i);
            forName = objCl.forClass().getName();

            if (objCl.getName().equals(forName)) {
                if (cl.getName().equals(objCl.getName())) {
                    return i;
                }
            } else {
                // there was a class replacement
                if (cl.getName().equals(forName)) {
                    return i;
                }
            }
        }
        return -1;
    }

    private void readObjectNoData(Object object, Class<?> cl, ObjectStreamClass classDesc)
            throws ObjectStreamException {

    }

    private void readObjectForClass(Object object, ObjectStreamClass classDesc)
            throws IOException, ClassNotFoundException {
    }

    /**
     * Reads an integer (32 bit) from the source stream.
     *
     * @return the integer value read from the source stream.
     * @throws EOFException
     *             if the end of the input is reached before the read
     *             request can be satisfied.
     * @throws IOException
     *             if an error occurs while reading from the source stream.
     */
    public int readInt() throws IOException {
        return primitiveTypes.readInt();
    }

    /**
     * Reads the next line from the source stream. Lines are terminated by
     * {@code '\r'}, {@code '\n'}, {@code "\r\n"} or an {@code EOF}.
     *
     * @return the string read from the source stream.
     * @throws IOException
     *             if an error occurs while reading from the source stream.
     * @deprecated Use {@link BufferedReader}
     */
    @Deprecated
    public String readLine() throws IOException {
        return primitiveTypes.readLine();
    }

    /**
     * Reads a long (64 bit) from the source stream.
     *
     * @return the long value read from the source stream.
     * @throws EOFException
     *             if the end of the input is reached before the read
     *             request can be satisfied.
     * @throws IOException
     *             if an error occurs while reading from the source stream.
     */
    public long readLong() throws IOException {
        return primitiveTypes.readLong();
    }

    /**
     * Read a new array from the receiver. It is assumed the array has not been
     * read yet (not a cyclic reference). Return the array read.
     *
     * @param unshared
     *            read the object unshared
     * @return the array read
     *
     * @throws IOException
     *             If an IO exception happened when reading the array.
     * @throws ClassNotFoundException
     *             If a class for one of the objects could not be found
     * @throws OptionalDataException
     *             If optional data could not be found when reading the array.
     */
    private Object readNewArray(boolean unshared) throws OptionalDataException,
            ClassNotFoundException, IOException {
        ObjectStreamClass classDesc = readClassDesc();

        if (classDesc == null) {
            throw new InvalidClassException(Messages.getString("luni.C1")); //$NON-NLS-1$
        }

        Integer newHandle = nextHandle();

        // Array size
        int size = input.readInt();
        Class<?> arrayClass = classDesc.forClass();
        Class<?> componentType = arrayClass.getComponentType();
        Object result = Array.newInstance(componentType, size);

        registerObjectRead(result, newHandle, unshared);

        // Now we have code duplication just because Java is typed. We have to
        // read N elements and assign to array positions, but we must typecast
        // the array first, and also call different methods depending on the
        // elements.
        if (componentType.isPrimitive()) {
            if (componentType == Integer.TYPE) {
                int[] intArray = (int[]) result;
                for (int i = 0; i < size; i++) {
                    intArray[i] = input.readInt();
                }
            } else if (componentType == Byte.TYPE) {
                byte[] byteArray = (byte[]) result;
                input.readFully(byteArray, 0, size);
            } else if (componentType == Character.TYPE) {
                char[] charArray = (char[]) result;
                for (int i = 0; i < size; i++) {
                    charArray[i] = input.readChar();
                }
            } else if (componentType == Short.TYPE) {
                short[] shortArray = (short[]) result;
                for (int i = 0; i < size; i++) {
                    shortArray[i] = input.readShort();
                }
            } else if (componentType == Boolean.TYPE) {
                boolean[] booleanArray = (boolean[]) result;
                for (int i = 0; i < size; i++) {
                    booleanArray[i] = input.readBoolean();
                }
            } else if (componentType == Long.TYPE) {
                long[] longArray = (long[]) result;
                for (int i = 0; i < size; i++) {
                    longArray[i] = input.readLong();
                }
            } else if (componentType == Float.TYPE) {
                float[] floatArray = (float[]) result;
                for (int i = 0; i < size; i++) {
                    floatArray[i] = input.readFloat();
                }
            } else if (componentType == Double.TYPE) {
                double[] doubleArray = (double[]) result;
                for (int i = 0; i < size; i++) {
                    doubleArray[i] = input.readDouble();
                }
            } else {
                throw new ClassNotFoundException(Messages.getString(
                        "luni.C2", classDesc.getName())); //$NON-NLS-1$
            }
        } else {
            // Array of Objects
            Object[] objectArray = (Object[]) result;
            for (int i = 0; i < size; i++) {
                objectArray[i] = readObject();
            }
        }
        if (enableResolve) {
            result = resolveObject(result);
            registerObjectRead(result, newHandle, false);
        }
        return result;
    }

    /**
     * Reads a new class from the receiver. It is assumed the class has not been
     * read yet (not a cyclic reference). Return the class read.
     *
     * @param unshared
     *            read the object unshared
     * @return The {@code java.lang.Class} read from the stream.
     *
     * @throws IOException
     *             If an IO exception happened when reading the class.
     * @throws ClassNotFoundException
     *             If a class for one of the objects could not be found
     */
    private Class<?> readNewClass(boolean unshared)
            throws ClassNotFoundException, IOException {
        ObjectStreamClass classDesc = readClassDesc();

        if (classDesc != null) {
            Class<?> localClass = classDesc.forClass();
            if (localClass != null) {
                registerObjectRead(localClass, nextHandle(), unshared);
            }
            return localClass;
        }
        throw new InvalidClassException(Messages.getString("luni.C1")); //$NON-NLS-1$
    }

    /*
     * read class type for Enum, note there's difference between enum and normal
     * classes
     */
    private ObjectStreamClass readEnumDesc() throws IOException,
            ClassNotFoundException {
        byte tc = nextTC();
        switch (tc) {
            case TC_CLASSDESC:
                return readEnumDescInternal();
            case TC_REFERENCE:
                return (ObjectStreamClass) readCyclicReference();
            case TC_NULL:
                return null;
            default:
                throw new StreamCorruptedException(Messages.getString(
                        "luni.BC", Integer.toHexString(tc & 0xff))); //$NON-NLS-1$
        }
    }

    private ObjectStreamClass readEnumDescInternal() throws IOException,
            ClassNotFoundException {
        ObjectStreamClass classDesc;
        primitiveData = input;
        Integer oldHandle = descriptorHandle;
        descriptorHandle = nextHandle();
        classDesc = readClassDescriptor();
        registerObjectRead(classDesc, descriptorHandle, false);
        descriptorHandle = oldHandle;
        primitiveData = emptyStream;
        // Consume unread class annotation data and TC_ENDBLOCKDATA
        discardData();
        ObjectStreamClass superClass = readClassDesc();
        checkedSetSuperClassDesc(classDesc, superClass);
        // Check SUIDs, note all SUID for Enum is 0L
        if (0L != classDesc.getSerialVersionUID()
                || 0L != superClass.getSerialVersionUID()) {
            throw new InvalidClassException(superClass.getName(), Messages
                    .getString("luni.C3", superClass, //$NON-NLS-1$
                            superClass));
        }
        byte tc = nextTC();
        // discard TC_ENDBLOCKDATA after classDesc if any
        if (tc == TC_ENDBLOCKDATA) {
            // read next parent class. For enum, it may be null
        } else {
            // not TC_ENDBLOCKDATA, push back for next read
            pushbackTC();
        }
        return classDesc;
    }

    @SuppressWarnings("unchecked")// For the Enum.valueOf call
    private Object readEnum(boolean unshared) throws
            ClassNotFoundException, IOException {
        // read classdesc for Enum first
        ObjectStreamClass classDesc = readEnumDesc();
        Integer newHandle = nextHandle();
        // read name after class desc
        String name;
        byte tc = nextTC();
        switch (tc) {
            case TC_REFERENCE:
                if (unshared) {
                    readNewHandle();
                    throw new InvalidObjectException(Messages.getString("luni.BE")); //$NON-NLS-1$
                }
                name = (String) readCyclicReference();
                break;
            case TC_STRING:
                name = (String) readNewString(unshared);
                break;
            default:
                throw new StreamCorruptedException(Messages.getString("luni.BC"));//$NON-NLS-1$
        }

        Enum<?> result = Enum.valueOf((Class) classDesc.forClass(), name);
        registerObjectRead(result, newHandle, unshared);

        return result;
    }

    /**
     * Reads a new class descriptor from the receiver. It is assumed the class
     * descriptor has not been read yet (not a cyclic reference). Return the
     * class descriptor read.
     *
     * @param unshared
     *            read the object unshared
     * @return The {@code ObjectStreamClass} read from the stream.
     *
     * @throws IOException
     *             If an IO exception happened when reading the class
     *             descriptor.
     * @throws ClassNotFoundException
     *             If a class for one of the objects could not be found
     */
    private ObjectStreamClass readNewClassDesc(boolean unshared)
            throws ClassNotFoundException, IOException {

        return null;
    }

    /**
     * Reads a new proxy class descriptor from the receiver. It is assumed the
     * proxy class descriptor has not been read yet (not a cyclic reference).
     * Return the proxy class descriptor read.
     *
     * @return The {@code Class} read from the stream.
     *
     * @throws IOException
     *             If an IO exception happened when reading the class
     *             descriptor.
     * @throws ClassNotFoundException
     *             If a class for one of the objects could not be found
     */
    private Class<?> readNewProxyClassDesc() throws ClassNotFoundException,
            IOException {
        int count = input.readInt();
        String[] interfaceNames = new String[count];
        for (int i = 0; i < count; i++) {
            interfaceNames[i] = input.readUTF();
        }
        Class<?> proxy = resolveProxyClass(interfaceNames);
        // Consume unread class annotation data and TC_ENDBLOCKDATA
        discardData();
        return proxy;
    }

    /**
     * Reads a class descriptor from the source stream.
     *
     * @return the class descriptor read from the source stream.
     * @throws ClassNotFoundException
     *             if a class for one of the objects cannot be found.
     * @throws IOException
     *             if an error occurs while reading from the source stream.
     */
    protected ObjectStreamClass readClassDescriptor() throws IOException,
            ClassNotFoundException {

        ObjectStreamClass newClassDesc = new ObjectStreamClass();
        String name = input.readUTF();
        if (name.length() == 0) {
            // luni.07 = The stream is corrupted
            throw new IOException(Messages.getString("luni.07")); //$NON-NLS-1$
        }

        /*
         * We must register the class descriptor before reading field
         * descriptors. If called outside of readObject, the descriptorHandle
         * might be null.
         */
        descriptorHandle = (null == descriptorHandle ? nextHandle() : descriptorHandle);
        registerObjectRead(newClassDesc, descriptorHandle, false);

        readFieldDescriptors(newClassDesc);
        return newClassDesc;
    }

    /**
     * Creates the proxy class that implements the interfaces specified in
     * {@code interfaceNames}.
     *
     * @param interfaceNames
     *            the interfaces used to create the proxy class.
     * @return the proxy class.
     * @throws ClassNotFoundException
     *             if the proxy class or any of the specified interfaces cannot
     *             be created.
     * @throws IOException
     *             if an error occurs while reading from the source stream.
     * @see ObjectOutputStream#annotateProxyClass(Class)
     */
    protected Class<?> resolveProxyClass(String[] interfaceNames)
            throws IOException, ClassNotFoundException {

        return null;
    }

    /**
     * Write a new handle describing a cyclic reference from the stream.
     *
     * @return the handle read
     *
     * @throws IOException
     *             If an IO exception happened when reading the handle
     */
    private int readNewHandle() throws IOException {
        return input.readInt();
    }

    private Class<?> resolveConstructorClass(Class<?> objectClass, boolean wasSerializable, boolean wasExternalizable)
            throws ClassNotFoundException, IOException {

        return null;
    }

    /**
     * Read a new object from the stream. It is assumed the object has not been
     * loaded yet (not a cyclic reference). Return the object read.
     *
     * If the object implements <code>Externalizable</code> its
     * <code>readExternal</code> is called. Otherwise, all fields described by
     * the class hierarchy are loaded. Each class can define how its declared
     * instance fields are loaded by defining a private method
     * <code>readObject</code>
     *
     * @param unshared
     *            read the object unshared
     * @return the object read
     *
     * @throws IOException
     *             If an IO exception happened when reading the object.
     * @throws OptionalDataException
     *             If optional data could not be found when reading the object
     *             graph
     * @throws ClassNotFoundException
     *             If a class for one of the objects could not be found
     */
    private Object readNewObject(boolean unshared)
            throws OptionalDataException, ClassNotFoundException, IOException {

        return null;
    }

    /**
     * Read a string encoded in {@link DataInput modified UTF-8} from the
     * receiver. Return the string read.
     *
     * @param unshared
     *            read the object unshared
     * @return the string just read.
     * @throws IOException
     *             If an IO exception happened when reading the String.
     */
    private Object readNewString(boolean unshared) throws IOException {
        Object result = input.readUTF();
        if (enableResolve) {
            result = resolveObject(result);
        }
        registerObjectRead(result, nextHandle(), unshared);

        return result;
    }

    /**
     * Read a new String in UTF format from the receiver. Return the string
     * read.
     *
     * @param unshared
     *            read the object unshared
     * @return the string just read.
     *
     * @throws IOException
     *             If an IO exception happened when reading the String.
     */
    private Object readNewLongString(boolean unshared) throws IOException {
        long length = input.readLong();
        Object result = null;

        return result;
    }

    /**
     * Reads the next object from the source stream.
     *
     * @return the object read from the source stream.
     * @throws ClassNotFoundException
     *             if the class of one of the objects in the object graph cannot
     *             be found.
     * @throws IOException
     *             if an error occurs while reading from the source stream.
     * @throws OptionalDataException
     *             if primitive data types were found instead of an object.
     * @see ObjectOutputStream#writeObject(Object)
     */
    public final Object readObject() throws OptionalDataException,
            ClassNotFoundException, IOException {
        return readObject(false);
    }

    /**
     * Reads the next unshared object from the source stream.
     *
     * @return the new object read.
     * @throws ClassNotFoundException
     *             if the class of one of the objects in the object graph cannot
     *             be found.
     * @throws IOException
     *             if an error occurs while reading from the source stream.
     * @see ObjectOutputStream#writeUnshared
     */
    public Object readUnshared() throws IOException, ClassNotFoundException {
        return readObject(true);
    }

    private Object readObject(boolean unshared) throws
            ClassNotFoundException, IOException {
        boolean restoreInput = (primitiveData == input);
        if (restoreInput) {
            primitiveData = emptyStream;
        }

        // This is the spec'ed behavior in JDK 1.2. Very bizarre way to allow
        // behavior overriding.
        if (subclassOverridingImplementation && !unshared) {
            return readObjectOverride();
        }

        // If we still had primitive types to read, should we discard them
        // (reset the primitiveTypes stream) or leave as is, so that attempts to
        // read primitive types won't read 'past data' ???
        Object result;
        try {
            // We need this so we can tell when we are returning to the
            // original/outside caller
            if (++nestedLevels == 1) {
                // Remember the caller's class loader
            }

            result = readNonPrimitiveContent(unshared);
            if (restoreInput) {
                primitiveData = input;
            }
        } finally {
            // We need this so we can tell when we are returning to the
            // original/outside caller
            if (--nestedLevels == 0) {
                // We are going to return to the original caller, perform
                // cleanups.
                // No more need to remember the caller's class loader
                callerClassLoader = null;
            }
        }

        // Done reading this object. Is it time to return to the original
        // caller? If so we need to perform validations first.
        if (nestedLevels == 0 && validations != null) {
            // We are going to return to the original caller. If validation is
            // enabled we need to run them now and then cleanup the validation
            // collection
            try {
                for (InputValidationDesc element : validations) {
                    element.validator.validateObject();
                }
            } finally {
                // Validations have to be renewed, since they are only called
                // from readObject
                validations = null;
            }
        }
        return result;
    }

    /**
     * Method to be overriden by subclasses to read the next object from the
     * source stream.
     *
     * @return the object read from the source stream.
     * @throws ClassNotFoundException
     *             if the class of one of the objects in the object graph cannot
     *             be found.
     * @throws IOException
     *             if an error occurs while reading from the source stream.
     * @throws OptionalDataException
     *             if primitive data types were found instead of an object.
     * @see ObjectOutputStream#writeObjectOverride
     */
    protected Object readObjectOverride() throws OptionalDataException,
            ClassNotFoundException, IOException {
        if (input == null) {
            return null;
        }
        // Subclasses must override.
        throw new IOException();
    }

    /**
     * Reads a short (16 bit) from the source stream.
     *
     * @return the short value read from the source stream.
     * @throws IOException
     *             if an error occurs while reading from the source stream.
     */
    public short readShort() throws IOException {
        return primitiveTypes.readShort();
    }

    /**
     * Reads and validates the ObjectInputStream header from the source stream.
     *
     * @throws IOException
     *             if an error occurs while reading from the source stream.
     * @throws StreamCorruptedException
     *             if the source stream does not contain readable serialized
     *             objects.
     */
    protected void readStreamHeader() throws IOException,
            StreamCorruptedException {
        if (input.readShort() == STREAM_MAGIC
                && input.readShort() == STREAM_VERSION) {
            return;
        }
        throw new StreamCorruptedException();
    }

    /**
     * Reads an unsigned byte (8 bit) from the source stream.
     *
     * @return the unsigned byte value read from the source stream packaged in
     *         an integer.
     * @throws EOFException
     *             if the end of the input is reached before the read
     *             request can be satisfied.
     * @throws IOException
     *             if an error occurs while reading from the source stream.
     */
    public int readUnsignedByte() throws IOException {
        return primitiveTypes.readUnsignedByte();
    }

    /**
     * Reads an unsigned short (16 bit) from the source stream.
     *
     * @return the unsigned short value read from the source stream packaged in
     *         an integer.
     * @throws EOFException
     *             if the end of the input is reached before the read
     *             request can be satisfied.
     * @throws IOException
     *             if an error occurs while reading from the source stream.
     */
    public int readUnsignedShort() throws IOException {
        return primitiveTypes.readUnsignedShort();
    }

    /**
     * Reads a string encoded in {@link DataInput modified UTF-8} from the
     * source stream.
     *
     * @return the string encoded in {@link DataInput modified UTF-8} read from
     *         the source stream.
     * @throws EOFException
     *             if the end of the input is reached before the read
     *             request can be satisfied.
     * @throws IOException
     *             if an error occurs while reading from the source stream.
     */
    public String readUTF() throws IOException {
        return primitiveTypes.readUTF();
    }

    /**
     * Return the object previously read tagged with handle {@code handle}.
     *
     * @param handle
     *            The handle that this object was assigned when it was read.
     * @return the object previously read.
     *
     * @throws InvalidObjectException
     *             If there is no previously read object with this handle
     */
    private Object registeredObjectRead(Integer handle)
            throws InvalidObjectException {
        Object res = objectsRead.get(handle);

        if (res == UNSHARED_OBJ) {
            throw new InvalidObjectException(Messages.getString("luni.C5")); //$NON-NLS-1$
        }

        return res;
    }

    /**
     * Assume object {@code obj} has been read, and assign a handle to
     * it, {@code handle}.
     *
     * @param obj
     *            Non-null object being loaded.
     * @param handle
     *            An Integer, the handle to this object
     * @param unshared
     *            Boolean, indicates that caller is reading in unshared mode
     *
     * @see #nextHandle
     */
    private void registerObjectRead(Object obj, Integer handle, boolean unshared) {
        objectsRead.put(handle, unshared ? UNSHARED_OBJ : obj);
    }

    /**
     * Registers a callback for post-deserialization validation of objects. It
     * allows to perform additional consistency checks before the {@code
     * readObject()} method of this class returns its result to the caller. This
     * method can only be called from within the {@code readObject()} method of
     * a class that implements "special" deserialization rules. It can be called
     * multiple times. Validation callbacks are then done in order of decreasing
     * priority, defined by {@code priority}.
     *
     * @param object
     *            an object that can validate itself by receiving a callback.
     * @param priority
     *            the validator's priority.
     * @throws InvalidObjectException
     *             if {@code object} is {@code null}.
     * @throws NotActiveException
     *             if this stream is currently not reading objects. In that
     *             case, calling this method is not allowed.
     * @see ObjectInputValidation#validateObject()
     */
    public synchronized void registerValidation(ObjectInputValidation object,
                                                int priority) throws NotActiveException, InvalidObjectException {
        // Validation can only be registered when inside readObject calls
        Object instanceBeingRead = this.currentObject;

        // We can't be called from just anywhere. There are rules.
        if (instanceBeingRead == null && nestedLevels == 0) {
            throw new NotActiveException();
        }
        if (object == null) {
            throw new InvalidObjectException(Messages.getString("luni.C6")); //$NON-NLS-1$
        }
        // From now on it is just insertion in a SortedCollection. Since
        // the Java class libraries don't provide that, we have to
        // implement it from scratch here.
        InputValidationDesc desc = new InputValidationDesc();
        desc.validator = object;
        desc.priority = priority;
        // No need for this, validateObject does not take a parameter
        // desc.toValidate = instanceBeingRead;
        if (validations == null) {
            validations = new InputValidationDesc[1];
            validations[0] = desc;
        } else {
            int i = 0;
            for (; i < validations.length; i++) {
                InputValidationDesc validation = validations[i];
                // Sorted, higher priority first.
                if (priority >= validation.priority) {
                    break; // Found the index where to insert
                }
            }
            InputValidationDesc[] oldValidations = validations;
            int currentSize = oldValidations.length;
            validations = new InputValidationDesc[currentSize + 1];
            System.arraycopy(oldValidations, 0, validations, 0, i);
            System.arraycopy(oldValidations, i, validations, i + 1, currentSize
                    - i);
            validations[i] = desc;
        }
    }

    /**
     * Reset the collection of objects already loaded by the receiver.
     */
    private void resetSeenObjects() {
        objectsRead = new HashMap<Integer, Object>();
        currentHandle = baseWireHandle;
        primitiveData = emptyStream;
    }

    /**
     * Reset the receiver. The collection of objects already read by the
     * receiver is reset, and internal structures are also reset so that the
     * receiver knows it is in a fresh clean state.
     */
    private void resetState() {
        resetSeenObjects();
        hasPushbackTC = false;
        pushbackTC = 0;
        // nestedLevels = 0;
    }

    /**
     * Loads the Java class corresponding to the class descriptor {@code
     * osClass} that has just been read from the source stream.
     *
     * @param osClass
     *            an ObjectStreamClass read from the source stream.
     * @return a Class corresponding to the descriptor {@code osClass}.
     * @throws ClassNotFoundException
     *             if the class for an object cannot be found.
     * @throws IOException
     *             if an I/O error occurs while creating the class.
     * @see ObjectOutputStream#annotateClass(Class)
     */
    protected Class<?> resolveClass(ObjectStreamClass osClass)
            throws IOException, ClassNotFoundException {
        // fastpath: obtain cached value
        Class<?> cls = osClass.forClass();
        if (null == cls) {
            // slowpath: resolve the class
            String className = osClass.getName();

            // if it is primitive class, for example, long.class
            cls = PRIMITIVE_CLASSES.get(className);

            if (null == cls) {
                // not primitive class
                // Use the first non-null ClassLoader on the stack. If null, use
                // the system class loader
                cls = Class.forName(className, true, callerClassLoader);
            }
        }
        return cls;
    }

    /**
     * Allows trusted subclasses to substitute the specified original {@code
     * object} with a new object. Object substitution has to be activated first
     * with calling {@code enableResolveObject(true)}. This implementation just
     * returns {@code object}.
     *
     * @param object
     *            the original object for which a replacement may be defined.
     * @return the replacement object for {@code object}.
     * @throws IOException
     *             if any I/O error occurs while creating the replacement
     *             object.
     * @see #enableResolveObject
     * @see ObjectOutputStream#enableReplaceObject
     * @see ObjectOutputStream#replaceObject
     */
    protected Object resolveObject(Object object) throws IOException {
        // By default no object replacement. Subclasses can override
        return object;
    }

    /**
     * Skips {@code length} bytes on the source stream. This method should not
     * be used to skip bytes at any arbitrary position, just when reading
     * primitive data types (int, char etc).
     *
     * @param length
     *            the number of bytes to skip.
     * @return the number of bytes actually skipped.
     * @throws IOException
     *             if an error occurs while skipping bytes on the source stream.
     * @throws NullPointerException
     *             if the source stream is {@code null}.
     */
    public int skipBytes(int length) throws IOException {
        // To be used with available. Ok to call if reading primitive buffer
        if (input == null) {
            throw new NullPointerException();
        }

        int offset = 0;
        while (offset < length) {
            checkReadPrimitiveTypes();
            long skipped = primitiveData.skip(length - offset);
            if (skipped == 0) {
                return offset;
            }
            offset += (int) skipped;
        }
        return length;
    }

    /**
     * Verify if the SUID & the base name for descriptor
     * <code>loadedStreamClass</code>matches
     * the SUID & the base name of the corresponding loaded class and
     * init private fields.
     *
     * @param loadedStreamClass
     *            An ObjectStreamClass that was loaded from the stream.
     *
     * @throws InvalidClassException
     *             If the SUID of the stream class does not match the VM class
     */
    private void verifyAndInit(ObjectStreamClass loadedStreamClass)
            throws InvalidClassException {

    }

    private static String getBaseName(String fullName) {
        int k = fullName.lastIndexOf('.');

        if (k == -1 || k == (fullName.length() - 1)) {
            return fullName;
        }
        return fullName.substring(k + 1);
    }

    // Avoid recursive defining.
    private static void checkedSetSuperClassDesc(ObjectStreamClass desc,
                                                 ObjectStreamClass superDesc) throws StreamCorruptedException {
    }

    public final TaintedReferenceWithObjTag readObject$$PHOSPHORTAGGED(Taint thisTaint, TaintedReferenceWithObjTag ret) throws
            ClassNotFoundException, IOException {
        return null;
    }

    public TaintedReferenceWithObjTag readObject$$PHOSPHORTAGGED(Taint emptyTaint, ControlFlowStack dummy, TaintedReferenceWithObjTag ret) {
        return null;
    }

}