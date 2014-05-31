package com.jayway.maven.plugins.android.phase05compile;

import com.jayway.maven.plugins.android.common.AndroidExtension;
import com.jayway.maven.plugins.android.common.ArtifactResolverHelper;
import com.jayway.maven.plugins.android.common.Const;
import com.jayway.maven.plugins.android.common.JarHelper;
import com.jayway.maven.plugins.android.common.NativeHelper;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.filefilter.TrueFileFilter;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.DefaultArtifact;
import org.apache.maven.artifact.handler.ArtifactHandler;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.Log;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Various helper methods for dealing with Android Native makefiles.
 *
 * @author Johan Lindquist
 */
public class MakefileHelper
{
    public static final String MAKEFILE_CAPTURE_FILE = "ANDROID_MAVEN_PLUGIN_LOCAL_C_INCLUDES_FILE";
    
    /**
     * Holder for the result of creating a makefile.  This in particular keep tracks of all directories created
     * for extracted header files.
     */
    public static class MakefileHolder
    {
        String makeFile;
        List<File> includeDirectories;

        public MakefileHolder( List<File> includeDirectories, String makeFile )
        {
            this.includeDirectories = includeDirectories;
            this.makeFile = makeFile;
        }

        public List<File> getIncludeDirectories()
        {
            return includeDirectories;
        }

        public String getMakeFile()
        {
            return makeFile;
        }
    }

    private final Log log;
    private final ArtifactResolverHelper artifactResolverHelper;
    private final ArtifactHandler harArtifactHandler;
    private final File unpackedApkLibsDirectory;
    
    /**
     * Initialize the MakefileHelper by storing the supplied parameters to local variables.
     * @param log                       Log to which to write log output.
     * @param artifactResolverHelper    ArtifactResolverHelper to use to resolve the artifacts.
     * @param harHandler                ArtifactHandler for har files.
     * @param unpackedApkLibsDirectory  Folder in which apklibs are unpacked.
     */
    public MakefileHelper( Log log, ArtifactResolverHelper artifactResolverHelper,
                           ArtifactHandler harHandler, File unpackedApkLibsDirectory )
    {
        this.log = log;
        this.artifactResolverHelper = artifactResolverHelper;
        this.harArtifactHandler = harHandler;
        this.unpackedApkLibsDirectory = unpackedApkLibsDirectory;
    }
    
    /**
     * Cleans up all include directories created in the temp directory during the build.
     *
     * @param makefileHolder The holder produced by the
     * {@link MakefileHelper#createMakefileFromArtifacts(Set, String, String, boolean)}
     */
    public static void cleanupAfterBuild( MakefileHolder makefileHolder )
    {

        if ( makefileHolder.getIncludeDirectories() != null )
        {
            for ( File file : makefileHolder.getIncludeDirectories() )
            {
                try
                {
                    FileUtils.deleteDirectory( file );
                }
                catch ( IOException e )
                {
                    e.printStackTrace();
                }
            }
        }

    }

    /**
     * Creates an Android Makefile based on the specified set of static library dependency artifacts.
     *
     * @param artifacts         The list of (static library) dependency artifacts to create the Makefile from
     * @param useHeaderArchives If true, the Makefile should include a LOCAL_EXPORT_C_INCLUDES statement, pointing to
     *                          the location where the header archive was expanded
     * @return The created Makefile
     */
    public MakefileHolder createMakefileFromArtifacts( Set<Artifact> artifacts,
                                                              String ndkArchitecture, String defaultNDKArchitecture,
                                                              boolean useHeaderArchives )
            throws IOException, MojoExecutionException
    {

        final StringBuilder makeFile = new StringBuilder( "# Generated by Android Maven Plugin\n" );
        final List<File> includeDirectories = new ArrayList<File>();

        // Add now output - allows us to somewhat intelligently determine the include paths to use for the header
        // archive
        makeFile.append( "$(shell echo \"LOCAL_C_INCLUDES=$(LOCAL_C_INCLUDES)\" > $(" + MAKEFILE_CAPTURE_FILE + "))" );
        makeFile.append( '\n' );
        makeFile.append( "$(shell echo \"LOCAL_PATH=$(LOCAL_PATH)\" >> $(" + MAKEFILE_CAPTURE_FILE + "))" );
        makeFile.append( '\n' );
        makeFile.append( "$(shell echo \"LOCAL_MODULE_FILENAME=$(LOCAL_MODULE_FILENAME)\" >> $("
                + MAKEFILE_CAPTURE_FILE + "))" );
        makeFile.append( '\n' );
        makeFile.append( "$(shell echo \"LOCAL_MODULE=$(LOCAL_MODULE)\" >> $(" + MAKEFILE_CAPTURE_FILE + "))" );
        makeFile.append( '\n' );
        makeFile.append( "$(shell echo \"LOCAL_CFLAGS=$(LOCAL_CFLAGS)\" >> $(" + MAKEFILE_CAPTURE_FILE + "))" );
        makeFile.append( '\n' );

        if ( ! artifacts.isEmpty() )
        {
            for ( Artifact artifact : artifacts )
            {
                final String architecture = NativeHelper.extractArchitectureFromArtifact( artifact,
                        defaultNDKArchitecture );

                makeFile.append( '\n' );
                makeFile.append( "ifeq ($(TARGET_ARCH_ABI)," ).append( architecture ).append( ")\n" );

                makeFile.append( "#\n" );
                makeFile.append( "# Group ID: " );
                makeFile.append( artifact.getGroupId() );
                makeFile.append( '\n' );
                makeFile.append( "# Artifact ID: " );
                makeFile.append( artifact.getArtifactId() );
                makeFile.append( '\n' );
                makeFile.append( "# Artifact Type: " );
                makeFile.append( artifact.getType() );
                makeFile.append( '\n' );
                makeFile.append( "# Version: " );
                makeFile.append( artifact.getVersion() );
                makeFile.append( '\n' );
                makeFile.append( "include $(CLEAR_VARS)" );
                makeFile.append( '\n' );
                makeFile.append( "LOCAL_MODULE    := " );
                makeFile.append( artifact.getArtifactId() );
                makeFile.append( '\n' );

                final boolean apklibStatic = addLibraryDetails( makeFile, artifact, ndkArchitecture );

                if ( useHeaderArchives )
                {
                    try
                    {
                        // Fix for dealing with APKLIBs - unfortunately it does not fully work since
                        // an APKLIB can contain any number of architectures making it somewhat to resolve the
                        // related (HAR) artifact.
                        //
                        // In this case, we construct the classifier from <architecture> and the artifact classifier
                        // if it is also present.  Only issue is that if the APKLIB contains more than the armeabi
                        // libraries (e.g. x86 for examples) the HAR is not resolved correctly.
                        //
                        String classifier = artifact.getClassifier();
                        if ( "apklib".equals( artifact.getType() ) )
                        {
                            classifier = ndkArchitecture;
                            if ( artifact.getClassifier() != null )
                            {
                                classifier += "-" + artifact.getClassifier();
                            }
                        }

                        Artifact harArtifact = new DefaultArtifact( artifact.getGroupId(), artifact.getArtifactId(),
                                artifact.getVersion(), artifact.getScope(),
                                Const.ArtifactType.NATIVE_HEADER_ARCHIVE, classifier,
                                harArtifactHandler );

                        File resolvedHarArtifactFile = artifactResolverHelper.resolveArtifactToFile( harArtifact );
                        log.debug( "Resolved har artifact file : " + resolvedHarArtifactFile );

                        final File includeDir = new File( System.getProperty( "java.io.tmpdir" ),
                                "android_maven_plugin_native_includes" + System.currentTimeMillis() + "_"
                                        + harArtifact.getArtifactId() );
                        includeDir.deleteOnExit();
                        includeDirectories.add( includeDir );

                        JarHelper.unjar( new JarFile( resolvedHarArtifactFile ), includeDir,
                                new JarHelper.UnjarListener()
                                {
                                    @Override
                                    public boolean include( JarEntry jarEntry )
                                    {
                                        return ! jarEntry.getName().startsWith( "META-INF" );
                                    }
                                } );

                        makeFile.append( "LOCAL_EXPORT_C_INCLUDES := " );
                        makeFile.append( includeDir.getAbsolutePath() );
                        makeFile.append( '\n' );
                        
                        if ( log.isDebugEnabled() )
                        {
                            Collection<File> includes = FileUtils.listFiles( includeDir,
                                    TrueFileFilter.INSTANCE, TrueFileFilter.INSTANCE );
                            log.debug( "Listing LOCAL_EXPORT_C_INCLUDES for " + artifact.getId() + ": " + includes );
                        }
                    }
                    catch ( RuntimeException e )
                    {
                        throw new MojoExecutionException(
                                "Error while resolving header archive file for: " + artifact.getArtifactId(), e );
                    }
                }
                if ( Const.ArtifactType.NATIVE_IMPLEMENTATION_ARCHIVE.equals( artifact.getType() ) || apklibStatic )
                {
                    makeFile.append( "include $(PREBUILT_STATIC_LIBRARY)\n" );
                }
                else
                {
                    makeFile.append( "include $(PREBUILT_SHARED_LIBRARY)\n" );
                }

                makeFile.append( "endif #" ).append( artifact.getClassifier() ).append( '\n' );
                makeFile.append( '\n' );

            }
        }
        
        return new MakefileHolder( includeDirectories, makeFile.toString() );
    }

    private boolean addLibraryDetails( StringBuilder makeFile,
                                       Artifact artifact, String ndkArchitecture ) throws IOException
    {
        boolean apklibStatic = false;
        if ( AndroidExtension.APKLIB.equals( artifact.getType() ) )
        {
            String classifier = artifact.getClassifier();
            String architecture = ( classifier != null ) ? classifier : ndkArchitecture;
            // 
            // We assume that APKLIB contains a single static OR shared library
            // that we should link against. The follow code identifies that file.
            //
            File[] staticLibs = NativeHelper.listNativeFiles( artifact, unpackedApkLibsDirectory, 
                                                              architecture, true );
            if ( staticLibs != null && staticLibs.length > 0 )
            {
                int libIdx = findApklibNativeLibrary( staticLibs, artifact.getArtifactId() );
                apklibStatic = true;
                addLibraryDetails( makeFile, staticLibs[libIdx], "" );
            }
            else
            {
                File[] sharedLibs = NativeHelper.listNativeFiles( artifact, unpackedApkLibsDirectory, 
                                                                  architecture, false );
                if ( sharedLibs == null )
                {
                    throw new IOException( "Failed to find any library file in APKLIB" );
                }
                int libIdx = findApklibNativeLibrary( sharedLibs, artifact.getArtifactId() );
                addLibraryDetails( makeFile, sharedLibs[libIdx], "" );
            }
        }
        else
        {
            addLibraryDetails( makeFile, artifact.getFile(), artifact.getArtifactId() );
        }

        return apklibStatic;
    }

    private void addLibraryDetails( StringBuilder makeFile, File libFile, String outputName )
        throws IOException
    {
        makeFile.append( "LOCAL_PATH := " );
        makeFile.append( libFile.getParentFile().getAbsolutePath() );
        makeFile.append( '\n' );
        makeFile.append( "LOCAL_SRC_FILES := " );
        makeFile.append( libFile.getName() );
        makeFile.append( '\n' );
        makeFile.append( "LOCAL_MODULE_FILENAME := " );
        if ( "".equals( outputName ) )
        {
            makeFile.append( FilenameUtils.removeExtension( libFile.getName() ) );
        }
        else
        {
            makeFile.append( outputName );
        }
        makeFile.append( '\n' );
    }

    /**
     * @param libs the array of possible library files. Must not be null.
     * @return the index in the array of the library to use
     * @throws IOException if a library cannot be identified
     */
    private int findApklibNativeLibrary( File[] libs, String artifactName ) throws IOException
    {
        int libIdx = -1;
        
        if ( libs.length == 1 )
        
        {
            libIdx = 0;
        }
        else
        {
            log.info( "Found multiple library files, looking for name match with artifact" );
            StringBuilder sb = new StringBuilder();
            for ( int i = 0; i < libs.length; i++ )
            {
                if ( sb.length() != 0 )
                {
                    sb.append( ", " );
                }
                sb.append( libs[i].getName() );
                if ( libs[i].getName().startsWith( "lib" + artifactName ) )
                {
                    if ( libIdx != -1 )
                    {
                        // We have multiple matches, tell the user we can't handle this ...
                        throw new IOException( "Found multiple libraries matching artifact name " + artifactName
                                + ". Please use unique artifact/library names." );
                        
                    }
                    libIdx = i;
                }
            }
            if ( libIdx < 0 )
            {
                throw new IOException( "Unable to determine main library from " + sb.toString()
                        + " APKLIB should contain only 1 library or a library matching the artifact name" );
            }
        }
        return libIdx;
    }

    /**
     * Creates a list of artifacts suitable for use in the LOCAL_STATIC_LIBRARIES or LOCAL_SHARED_LIBRARIES 
     * variable in an Android makefile
     *
     * @param resolvedLibraryList
     * @param staticLibrary
     * @return a list of Ids for artifacts that include static or shared libraries
     */
    public String createLibraryList( Set<Artifact> resolvedLibraryList,
                                     String ndkArchitecture,
                                     boolean staticLibrary )
    {
        Set<String> libraryNames = new LinkedHashSet<String>();

        for ( Artifact a : resolvedLibraryList )
        {
            if ( staticLibrary && Const.ArtifactType.NATIVE_IMPLEMENTATION_ARCHIVE.equals( a.getType() ) )
            {
                libraryNames.add( a.getArtifactId() );
            }
            if ( ! staticLibrary && Const.ArtifactType.NATIVE_SYMBOL_OBJECT.equals( a.getType() ) )
            {
                libraryNames.add( a.getArtifactId() );
            }
            if ( AndroidExtension.APKLIB.equals( a.getType() ) || AndroidExtension.AAR.equals( a.getType() ) )
            {
                File[] libFiles = NativeHelper.listNativeFiles( a, unpackedApkLibsDirectory, 
                                                                ndkArchitecture, staticLibrary );
                if ( libFiles != null && libFiles.length > 0 )
                {
                    libraryNames.add( a.getArtifactId() );
                }
                
            }
        }

        StringBuilder sb = new StringBuilder();

        Iterator<String> iter = libraryNames.iterator();

        while ( iter.hasNext() )
        {
            sb.append( iter.next() );

            if ( iter.hasNext() )
            {
                sb.append( " " );
            }
        }

        return sb.toString();
    }
}
