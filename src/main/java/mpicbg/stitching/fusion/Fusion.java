package mpicbg.stitching.fusion;

import fiji.stacks.Hyperstack_rearranger;
import ij.CompositeImage;
import ij.IJ;
import ij.ImageJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.io.FileSaver;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Vector;
import java.util.concurrent.atomic.AtomicInteger;

import net.imglib2.container.array.ArrayContainerFactory;
import net.imglib2.container.imageplus.ImagePlusContainer;
import net.imglib2.container.imageplus.ImagePlusContainerFactory;
import net.imglib2.cursor.LocalizableByDimCursor;
import net.imglib2.cursor.LocalizableCursor;
import net.imglib2.exception.ImgLibException;
import net.imglib2.image.Image;
import net.imglib2.image.ImageFactory;
import net.imglib2.image.display.imagej.ImageJFunctions;
import net.imglib2.interpolation.Interpolator;
import net.imglib2.interpolation.InterpolatorFactory;
import net.imglib2.interpolation.linear.LinearInterpolatorFactory;
import net.imglib2.interpolation.nearestneighbor.NearestNeighborInterpolatorFactory;
import net.imglib2.multithreading.Chunk;
import net.imglib2.multithreading.SimpleMultiThreading;
import net.imglib2.outofbounds.OutOfBoundsStrategyMirrorFactory;
import net.imglib2.outofbounds.OutOfBoundsStrategyValueFactory;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.integer.UnsignedByteType;
import net.imglib2.type.numeric.integer.UnsignedShortType;
import net.imglib2.type.numeric.real.FloatType;
import mpicbg.models.InvertibleBoundable;
import mpicbg.models.NoninvertibleModelException;
import stitching.utils.CompositeImageFixer;

/**
 * Manages the fusion for all types except the overlayfusion
 * 
 * @author Stephan Preibisch (stephan.preibisch@gmx.de)
 *
 */
public class Fusion 
{
	/**
	 * 
	 * @param targetType
	 * @param images
	 * @param models
	 * @param dimensionality
	 * @param subpixelResolution - if there is no subpixel resolution, we do not need to convert to float as no interpolation is necessary, we can compute everything with RealType
	 */
	public static < T extends RealType< T > > ImagePlus fuse( final T targetType, final ArrayList< ImagePlus > images, final ArrayList< InvertibleBoundable > models, 
			final int dimensionality, final boolean subpixelResolution, final int fusionType, final String outputDirectory, final boolean noOverlap, final boolean ignoreZeroValues )
	{
		// first we need to estimate the boundaries of the new image
		final float[] offset = new float[ dimensionality ];
		final int[] size = new int[ dimensionality ];
		final int numTimePoints = images.get( 0 ).getNFrames();
		final int numChannels = images.get( 0 ).getNChannels();
		
		estimateBounds( offset, size, images, models, dimensionality );
		
		if ( subpixelResolution )
			for ( int d = 0; d < size.length; ++d )
				++size[ d ];
		
		// for output
		final ImageFactory<T> f = new ImageFactory<T>( targetType, new ImagePlusContainerFactory() );
		
		// the final composite
		final ImageStack stack;
		
		// there is no output if we write to disk
		if ( outputDirectory == null )
			stack = new ImageStack( size[ 0 ], size[ 1 ] );
		else
			stack = null;

		//"Overlay into composite image"
		for ( int t = 1; t <= numTimePoints; ++t )
		{
			for ( int c = 1; c <= numChannels; ++c )
			{
				IJ.showStatus("Fusing time point: " + t + " of " + numTimePoints + ", " +
					"channel: " + c + " of " + numChannels + "...");
				// create the 2d/3d target image for the current channel and timepoint 
				final Image< T > out;
				
				// we just create one slice if we write to disk
				if ( outputDirectory == null )
					out = f.createImage( size );
				else
					out = f.createImage( new int[] { size[ 0 ], size[ 1 ] } ); // just create a slice

				// init the fusion
				PixelFusion fusion = null;
				
				if ( fusionType == 1 )
				{
					if ( ignoreZeroValues )
						fusion = new AveragePixelFusionIgnoreZero();
					else
						fusion = new AveragePixelFusion();
				}
				else if ( fusionType == 2 )
				{
					if ( ignoreZeroValues )
						fusion = new MedianPixelFusionIgnoreZero();
					else
						fusion = new MedianPixelFusion();
				}
				else if ( fusionType == 3 )
				{
					if ( ignoreZeroValues )
						fusion = new MaxPixelFusionIgnoreZero();
					else
						fusion = new MaxPixelFusion();
				}
				else if ( fusionType == 4 )
				{
					if ( ignoreZeroValues )
						fusion = new MinPixelFusionIgnoreZero();
					else
						fusion = new MinPixelFusion();	
				}
				
				// extract the complete blockdata
				if ( subpixelResolution )
				{
					final ArrayList< ImageInterpolation< FloatType > > blockData = new ArrayList< ImageInterpolation< FloatType > >();

					// for linear interpolation we want to mirror, otherwise we get black areas at the first and last pixel of each image
					final InterpolatorFactory< FloatType > interpolatorFactory = new LinearInterpolatorFactory<FloatType>( new OutOfBoundsStrategyMirrorFactory<FloatType>() );
					
					for ( final ImagePlus imp : images )
						blockData.add( new ImageInterpolation<FloatType>( ImageJFunctions.convertFloat( Hyperstack_rearranger.getImageChunk( imp, c, t ) ), interpolatorFactory ) );
					
					// init blending with the images
					if ( fusionType == 0 )
					{
						if ( ignoreZeroValues )
							fusion = new BlendingPixelFusionIgnoreZero( blockData );
						else
							fusion = new BlendingPixelFusion( blockData );
					}
					
					if ( outputDirectory == null )
					{
						fuseBlock( out, blockData, offset, models, fusion );
					}
					else
					{
						final int numSlices;
						
						if ( dimensionality == 2 )
							numSlices = 1;
						else
							numSlices = size[ 2 ];
						
						writeBlock( out, numSlices, t, numTimePoints, c, numChannels, blockData, offset, models, fusion, outputDirectory );
						out.close();
					}
				}
				else
				{
					// can be a mixture of different RealTypes
					final ArrayList< ImageInterpolation< ? extends RealType< ? > > > blockData = new ArrayList< ImageInterpolation< ? extends RealType< ? > > >();

					final InterpolatorFactory< FloatType > interpolatorFactoryFloat = new NearestNeighborInterpolatorFactory< FloatType >( new OutOfBoundsStrategyValueFactory<FloatType>() );
					final InterpolatorFactory< UnsignedShortType > interpolatorFactoryShort = new NearestNeighborInterpolatorFactory< UnsignedShortType >( new OutOfBoundsStrategyValueFactory<UnsignedShortType>() );
					final InterpolatorFactory< UnsignedByteType > interpolatorFactoryByte = new NearestNeighborInterpolatorFactory< UnsignedByteType >( new OutOfBoundsStrategyValueFactory<UnsignedByteType>() );

					for ( final ImagePlus imp : images )
					{
						if ( imp.getType() == ImagePlus.GRAY32 )
							blockData.add( new ImageInterpolation<FloatType>( ImageJFunctions.wrapFloat( Hyperstack_rearranger.getImageChunk( imp, c, t ) ), interpolatorFactoryFloat ) );
						else if ( imp.getType() == ImagePlus.GRAY16 )
							blockData.add( new ImageInterpolation<UnsignedShortType>( ImageJFunctions.wrapShort( Hyperstack_rearranger.getImageChunk( imp, c, t ) ), interpolatorFactoryShort ) );
						else
							blockData.add( new ImageInterpolation<UnsignedByteType>( ImageJFunctions.wrapByte( Hyperstack_rearranger.getImageChunk( imp, c, t ) ), interpolatorFactoryByte ) );
					}
					
					// init blending with the images
					if ( fusionType == 0 )
					{
						if ( ignoreZeroValues )
							fusion = new BlendingPixelFusionIgnoreZero( blockData );
						else
							fusion = new BlendingPixelFusion( blockData );					
					}
					
					if ( outputDirectory == null )
					{
						if ( noOverlap )
							fuseBlockNoOverlap( out, blockData, offset, models );
						else
							fuseBlock( out, blockData, offset, models, fusion );
					}
					else
					{
						final int numSlices;
						
						if ( dimensionality == 2 )
							numSlices = 1;
						else
							numSlices = size[ 2 ];
						
						writeBlock( out, numSlices, t, numTimePoints, c, numChannels, blockData, offset, models, fusion, outputDirectory );
						out.close();
					}
				}
				
				// add to stack
				try 
				{
					if ( stack != null )
					{
						final ImagePlus outImp = ((ImagePlusContainer<?,?>)out.getContainer()).getImagePlus();
						for ( int z = 1; z <= out.getDimension( 2 ); ++z )
							stack.addSlice( "", outImp.getStack().getProcessor( z ) );
					}
				} 
				catch (ImgLibException e) 
				{
					IJ.log( "Output image has no ImageJ type: " + e );
				}				
			}
		}

		IJ.showStatus("Fusion complete.");

		// has been written to disk ...
		if ( stack == null )
			return null;
		
		//convertXYZCT ...
		ImagePlus result = new ImagePlus( "", stack );
		
		// numchannels, z-slices, timepoints (but right now the order is still XYZCT)
		if ( dimensionality == 3 )
		{
			result.setDimensions( size[ 2 ], numChannels, numTimePoints );
			result = OverlayFusion.switchZCinXYCZT( result );
			return CompositeImageFixer.makeComposite( result, CompositeImage.COMPOSITE );
		}
		//IJ.log( "ch: " + imp.getNChannels() );
		//IJ.log( "slices: " + imp.getNSlices() );
		//IJ.log( "frames: " + imp.getNFrames() );
		result.setDimensions( numChannels, 1, numTimePoints );
		
		if ( numChannels > 1 || numTimePoints > 1 )
			return CompositeImageFixer.makeComposite( result, CompositeImage.COMPOSITE );
		return result;
	}
	
	/**
	 * Fuse one slice/volume (one channel)
	 * 
	 * @param output - same the type of the ImagePlus input
	 * @param input - FloatType, because of Interpolation that needs to be done
	 * @param transform - the transformation
	 */
	protected static <T extends RealType<T>> void fuseBlock( final Image<T> output, final ArrayList< ? extends ImageInterpolation< ? extends RealType< ? > > > input, final float[] offset, 
			final ArrayList< InvertibleBoundable > transform, final PixelFusion fusion )
	{
		final int numDimensions = output.getNumDimensions();
		final int numImages = input.size();
		long imageSize = output.getDimension( 0 );
		
		for ( int d = 1; d < output.getNumDimensions(); ++d )
			imageSize *= output.getDimension( d );
		
		final long steps = imageSize;

		// global progress variable. Needs to be final to be used in subordinate
		// threads, but needs to be an object so it can be passed by value and updated
		// statically.
		final int[] globalProgress = {0};
		IJ.showProgress(0);

		final int[][] max = new int[ numImages ][ numDimensions ];
		for ( int i = 0; i < numImages; ++i )
			for ( int d = 0; d < numDimensions; ++d )
				max[ i ][ d ] = input.get( i ).getImage().getDimension( d ) - 1; 
		
		// run multithreaded
		final AtomicInteger ai = new AtomicInteger(0);					
        final Thread[] threads = SimpleMultiThreading.newThreads();

        final Vector<Chunk> threadChunks = SimpleMultiThreading.divideIntoChunks( imageSize, threads.length );
        
        for (int ithread = 0; ithread < threads.length; ++ithread)
            threads[ithread] = new Thread(new Runnable()
            {
                @Override
                public void run()
                {
                	// Thread ID
                	final int myNumber = ai.getAndIncrement();
        
                	// get chunk of pixels to process
                	final Chunk myChunk = threadChunks.get( myNumber );
                	final long startPos = myChunk.getStartPosition();
                	final long loopSize = myChunk.getLoopSize();
                	
            		final LocalizableCursor<T> out = output.createLocalizableCursor();
            		final ArrayList<Interpolator<? extends RealType<?>>> in = new ArrayList<Interpolator<? extends RealType<?>>>();
            		
            		for ( int i = 0; i < numImages; ++i )
            			in.add( input.get( i ).createInterpolator() );
            		
            		final float[][] tmp = new float[ numImages ][ output.getNumDimensions() ];
            		final PixelFusion myFusion = fusion.copy();
            		
            		// tracks the progress [0-100/#threads] made by this thread
            		int[] localProgress = {0};
            		
            		// number of pixels processed
            		long stepsTaken = 0;
            		
            		try 
            		{
                		// move to the starting position of the current thread
            			out.fwd( startPos );
            			
                		// do as many pixels as wanted by this thread
                        for ( long j = 0; j < loopSize; ++j )
                        {
            				out.fwd();
            				stepsTaken++;
            				
            				// update status message if necessary
            				updateStatus(globalProgress, localProgress, stepsTaken, steps, output);
            				
            				// get the current position in the output image
            				for ( int d = 0; d < numDimensions; ++d )
            				{
            					final float value = out.getPosition( d ) + offset[ d ];
            					
            					for ( int i = 0; i < numImages; ++i )
            						tmp[ i ][ d ] = value;
            				}
            				
            				// transform and compute output value
            				myFusion.clear();
            				
            				// loop over all images for this output location
A:        					for ( int i = 0; i < numImages; ++i )
        					{
        						transform.get( i ).applyInverseInPlace( tmp[ i ] );
            	
        						// test if inside
        						for ( int d = 0; d < numDimensions; ++d )
        							if ( tmp[ i ][ d ] < 0 || tmp[ i ][ d ] > max[ i ][ d ] )
        								continue A;
        						
        						in.get( i ).setPosition( tmp[ i ] );			
        						myFusion.addValue( in.get( i ).getType().getRealFloat(), i, tmp[ i ] );
        					}
            				
            				// set value
    						out.getType().setReal( myFusion.getValue() );
                        }
            		} 
            		catch ( NoninvertibleModelException e ) 
            		{
            			IJ.log( "Cannot invert model, qutting." );
            			return;
            		}

                }
            });
        
        SimpleMultiThreading.startAndJoin( threads );
	}

	/**
	 * Fuse one slice/volume (one channel)
	 * 
	 * @param output - same the type of the ImagePlus input
	 * @param input - FloatType, because of Interpolation that needs to be done
	 * @param transform - the transformation
	 */
	protected static <T extends RealType<T>> void fuseBlockNoOverlap( final Image<T> output, final ArrayList< ? extends ImageInterpolation< ? extends RealType< ? > > > input, final float[] offset, 
			final ArrayList< InvertibleBoundable > transform )
	{
		final int numDimensions = output.getNumDimensions();
		final int numImages = input.size();
		long imageSize = output.getDimension( 0 );
		
		for ( int d = 1; d < output.getNumDimensions(); ++d )
			imageSize *= output.getDimension( d );
		
		final long steps = imageSize;
		
		// global progress variable. See #fuseBlock
		final int[] globalProgress = {0};
		IJ.showProgress(0);
		
		// run multithreaded
		final AtomicInteger ai = new AtomicInteger(0);					
        final Thread[] threads = SimpleMultiThreading.newThreads( numImages );

        for (int ithread = 0; ithread < threads.length; ++ithread)
            threads[ithread] = new Thread( new Runnable()
            {
                @Override
                public void run()
                {
                	// Thread ID
                	final int myImage = ai.getAndIncrement();
        
                	final Image< ? extends RealType<?> > image = input.get( myImage ).getImage();
                	final int[] translation = new int[ numDimensions ];
                	
                	final InvertibleBoundable t = transform.get( myImage );
            		final float[] tmp = new float[ numDimensions ];
            		t.applyInPlace( tmp );
 
            		for ( int d = 0; d < numDimensions; ++d )
            			translation[ d ] = Math.round( tmp[ d ] );

            		final LocalizableCursor< ? extends RealType<?> > cursor = image.createLocalizableCursor();
            		final LocalizableByDimCursor< ? extends RealType<?> > randomAccess = output.createLocalizableByDimCursor();
            		final int[] pos = new int[ numDimensions ];
            		// tracks the progress [0-100/#threads] made by this thread
            		int[] localProgress = {0};
            		
            		// number of pixels processed
            		long stepsTaken = 0;
            		
            		while ( cursor.hasNext() )
            		{
            			cursor.fwd();
            			cursor.getPosition( pos );
          				stepsTaken++;
          				
          				// update status message if necessary
          				updateStatus(globalProgress, localProgress, stepsTaken, steps, output);
          				
                		for ( int d = 0; d < numDimensions; ++d )
                		{
                			pos[ d ] += translation[ d ];
                			pos[ d ] -= offset[ d ];
                		}
                		
                		randomAccess.setPosition( pos );
                		randomAccess.getType().setReal( cursor.getType().getRealFloat() );
            		}           		
                 }
            });
        
        SimpleMultiThreading.startAndJoin( threads );
	}

	/**
	 * Fuse one slice/volume (one channel)
	 * 
	 * @param outputSlice - same the type of the ImagePlus input, just one slice which will be written to the output directory
	 * @param input - FloatType, because of Interpolation that needs to be done
	 * @param transform - the transformation
	 */
	protected static <T extends RealType<T>> void writeBlock( final Image<T> outputSlice, final int numSlices, final int t, final int numTimePoints, final int c, final int numChannels, 
			final ArrayList< ? extends ImageInterpolation< ? extends RealType< ? > > > input, final float[] offset, 
			final ArrayList< InvertibleBoundable > transform, final PixelFusion fusion, final String outputDirectory )
	{
		final int numImages = input.size();
		final int numDimensions = offset.length;
		long imageSize = outputSlice.getDimension( 0 );
		
		for ( int d = 1; d < outputSlice.getNumDimensions(); ++d )
			imageSize *= outputSlice.getDimension( d );

		// the maximal dimensions of each image
		final int[][] max = new int[ numImages ][ numDimensions ];
		for ( int i = 0; i < numImages; ++i )
			for ( int d = 0; d < numDimensions; ++d )
				max[ i ][ d ] = input.get( i ).getImage().getDimension( d ) - 1; 
		
		final LocalizableCursor<T> out = outputSlice.createLocalizableCursor();
		final ArrayList<Interpolator<? extends RealType<?>>> in = new ArrayList<Interpolator<? extends RealType<?>>>();
		
		for ( int i = 0; i < numImages; ++i )
			in.add( input.get( i ).createInterpolator() );
		
		final float[][] tmp = new float[ numImages ][ numDimensions ];
		final PixelFusion myFusion = fusion.copy();
		
		try 
		{
			for ( int slice = 0; slice < numSlices; ++slice )
			{
				IJ.showStatus("Fusing time point: " + t + " of " + numTimePoints + ", " +
						"channel: " + c + " of " + numChannels + ", slice: " + (slice + 1) + " of " +
						numSlices + "...");
				out.reset();
				int stepsTaken = 0;
				// Writing occurs on one thread only, so these can both be declared here.
				int[] localProgress = {0};
				int[] globalProgress = {0};
				IJ.showProgress(0);
				
				// fill all pixels of the current slice
				while ( out.hasNext() )
				{
					out.fwd();
  				stepsTaken++;
  				
  				// update status message if necessary
  				updateStatus(globalProgress, localProgress, stepsTaken, imageSize, outputSlice);
					
					// get the current position in the output image
					for ( int d = 0; d < 2; ++d )
					{
						final float value = out.getPosition( d ) + offset[ d ];
						
						for ( int i = 0; i < numImages; ++i )
							tmp[ i ][ d ] = value;
					}
					
					// if there is a third dimension, use the slice index
					if ( numDimensions == 3 )
					{
						final float value = slice + offset[ 2 ];
						
						for ( int i = 0; i < numImages; ++i )
							tmp[ i ][ 2 ] = value;						
					}
					
					// transform and compute output value
					myFusion.clear();
					
					// loop over all images for this output location
A:		        	for ( int i = 0; i < numImages; ++i )
		        	{
		        		transform.get( i ).applyInverseInPlace( tmp[ i ] );
		            	
		        		// test if inside
						for ( int d = 0; d < numDimensions; ++d )
							if ( tmp[ i ][ d ] < 0 || tmp[ i ][ d ] > max[ i ][ d ] )
								continue A;
						
						in.get( i ).setPosition( tmp[ i ] );			
						myFusion.addValue( in.get( i ).getType().getRealFloat(), i, tmp[ i ] );
					}
					
					// set value
					out.getType().setReal( myFusion.getValue() );
				}
				
				// write the slice
				final ImagePlus outImp = ((ImagePlusContainer<?,?>)outputSlice.getContainer()).getImagePlus();
				final FileSaver fs = new FileSaver( outImp );
				fs.saveAsTiff( new File( outputDirectory, "img_t" + lz( t, numTimePoints ) + "_z" + lz( slice+1, numSlices ) + "_c" + lz( c, numChannels ) ).getAbsolutePath() );
			}
		} 
		catch ( NoninvertibleModelException e ) 
		{
			IJ.log( "Cannot invert model, qutting." );
			return;
		} 
		catch ( ImgLibException e ) 
		{
			IJ.log( "Output image has no ImageJ type: " + e );
			return;
		}
	}

	private static final String lz( final int num, final int max )
	{
		String out = "" + num;
		String outMax = "" + max;
		
		while ( out.length() < outMax.length() )
			out = "0" + out;
		
		return out;
	}

	/**
	 * Estimate the bounds of the output image. If there are more models than images, we assume that this encodes for more timepoints.
	 * E.g. 2 Images and 10 models would mean 5 timepoints. The arrangement of the models should be as follows:
	 * 
	 * image1 timepoint1
	 * image2 timepoint1
	 * image1 timepoint2
	 * ...
	 * image2 timepoint5
	 * 
	 * @param offset - the offset, will be computed
	 * @param size - the size, will be computed
	 * @param images - all imageplus in a list
	 * @param models - all models
	 * @param dimensionality - which dimensionality (2 or 3)
	 */
	public static void estimateBounds( final float[] offset, final int[] size, final List<ImagePlus> images, final ArrayList<InvertibleBoundable> models, final int dimensionality )
	{
		final int[][] imgSizes = new int[ images.size() ][ dimensionality ];
		
		for ( int i = 0; i < images.size(); ++i )
		{
			imgSizes[ i ][ 0 ] = images.get( i ).getWidth();
			imgSizes[ i ][ 1 ] = images.get( i ).getHeight();
			if ( dimensionality == 3 )
				imgSizes[ i ][ 2 ] = images.get( i ).getNSlices();
		}
		
		estimateBounds( offset, size, imgSizes, models, dimensionality );
	}
	
	/**
	 * Estimate the bounds of the output image. If there are more models than images, we assume that this encodes for more timepoints.
	 * E.g. 2 Images and 10 models would mean 5 timepoints. The arrangement of the models should be as follows:
	 * 
	 * image1 timepoint1
	 * image2 timepoint1
	 * image1 timepoint2
	 * ...
	 * image2 timepoint5
	 * 
	 * @param offset - the offset, will be computed
	 * @param size - the size, will be computed
	 * @param imgSizes - the dimensions of all input images imgSizes[ image ][ x, y, (z) ]
	 * @param models - all models
	 * @param dimensionality - which dimensionality (2 or 3)
	 */
	public static void estimateBounds( final float[] offset, final int[] size, final int[][]imgSizes, final ArrayList<InvertibleBoundable> models, final int dimensionality )
	{
		final int numImages = imgSizes.length;
		final int numTimePoints = models.size() / numImages;
		
		// estimate the bounaries of the output image
		final float[][] max = new float[ numImages * numTimePoints ][];
		final float[][] min = new float[ numImages * numTimePoints ][ dimensionality ];
		
		if ( dimensionality == 2 )
		{
			for ( int i = 0; i < numImages * numTimePoints; ++i )
				max[ i ] = new float[] { imgSizes[ i % numImages ][ 0 ], imgSizes[ i % numImages ][ 1 ] };
		}
		else
		{
			for ( int i = 0; i < numImages * numTimePoints; ++i )
				max[ i ] = new float[] { imgSizes[ i % numImages ][ 0 ], imgSizes[ i % numImages ][ 1 ], imgSizes[ i % numImages ][ 2 ] };
		}
		
		//IJ.log( "1: " + Util.printCoordinates( min[ 0 ] ) + " -> " + Util.printCoordinates( max[ 0 ] ) );
		//IJ.log( "2: " + Util.printCoordinates( min[ 1 ] ) + " -> " + Util.printCoordinates( max[ 1 ] ) );

		// casts of the models
		final ArrayList<InvertibleBoundable> boundables = new ArrayList<InvertibleBoundable>();
		
		for ( int i = 0; i < numImages * numTimePoints; ++i )
		{
			final InvertibleBoundable boundable = models.get( i ); 
			boundables.add( boundable );
			
			//IJ.log( "i: " + boundable );
			
			boundable.estimateBounds( min[ i ], max[ i ] );
		}
		//IJ.log( "1: " + Util.printCoordinates( min[ 0 ] ) + " -> " + Util.printCoordinates( max[ 0 ] ) );
		//IJ.log( "2: " + Util.printCoordinates( min[ 1 ] ) + " -> " + Util.printCoordinates( max[ 1 ] ) );
		
		// dimensions of the final image
		final float[] minImg = new float[ dimensionality ];
		final float[] maxImg = new float[ dimensionality ];

		if ( max.length == 1 )
		{
			// just one image
			for ( int d = 0; d < dimensionality; ++d )
			{
				maxImg[ d ] = Math.max( max[ 0 ][ d ], min[ 0 ][ d ] );
				minImg[ d ] = Math.min( max[ 0 ][ d ], min[ 0 ][ d ] );				
			}
		}
		else
		{
			for ( int d = 0; d < dimensionality; ++d )
			{
				// the image might be rotated so that min is actually max
				maxImg[ d ] = Math.max( Math.max( max[ 0 ][ d ], max[ 1 ][ d ] ), Math.max( min[ 0 ][ d ], min[ 1 ][ d ]) );
				minImg[ d ] = Math.min( Math.min( max[ 0 ][ d ], max[ 1 ][ d ] ), Math.min( min[ 0 ][ d ], min[ 1 ][ d ]) );
				
				for ( int i = 2; i < numImages * numTimePoints; ++i )
				{
					maxImg[ d ] = Math.max( maxImg[ d ], Math.max( min[ i ][ d ], max[ i ][ d ]) );
					minImg[ d ] = Math.min( minImg[ d ], Math.min( min[ i ][ d ], max[ i ][ d ]) );	
				}
			}
		}
		//IJ.log( "output: " + Util.printCoordinates( minImg ) + " -> " + Util.printCoordinates( maxImg ) );

		// the size of the new image
		//final int[] size = new int[ dimensionality ];
		// the offset relative to the output image which starts with its local coordinates (0,0,0)
		//final float[] offset = new float[ dimensionality ];
		
		for ( int d = 0; d < dimensionality; ++d )
		{
			size[ d ] = Math.round( maxImg[ d ] - minImg[ d ] );
			offset[ d ] = minImg[ d ];			
		}
		
		//IJ.log( "size: " + Util.printCoordinates( size ) );
		//IJ.log( "offset: " + Util.printCoordinates( offset ) );		
	}

	/**
	 * Threadsafe method to display ImageJ status updates. Takes a global progress
	 * and local progress value (between 0 and 100), by reference, which are
	 * updated if the given localPosition, relative to the global maximum value,
	 * is a worthy increase in local progress (in which case both local and
	 * global progress is updated).
	 * <p>
	 * The global/local progress split must be used
	 * when running multithreaded, as it allows the local position to be
	 * maintained per thread, instead of requiring a global position to be
	 * maintained (which would require synchronized updates every step, which
	 * effectively would nullify any multithreading benefits).
	 * </p>
	 * <p>
	 * NB: will only enter a synchronized block if there is progress to report,
	 * and only once per progress milestone. Synchronization will be locked on
	 * the provided Image object, so multiple fusions can operate simultaneously
	 * on separate images.
	 * </p>
	 */
	private static void updateStatus(int[] globalProgress, int[] localProgress,
		long localPosition, long globalMax, Image<?> imageLock) {
		// assume a 0-100 % based update granularity
		final int updates = 100;
		// Compute the current progress. The actual value relative to the global
		// is irrelevant. But if local progress is made, it implies global progress
		// has been made.
		final int currentProgress = (int)((double)localPosition / 
				(globalMax - 1) * updates);

		if (currentProgress > localProgress[0]) {
			synchronized(imageLock) {
				if (currentProgress > localProgress[0]) {
					localProgress[0]++;
					globalProgress[0]++;
					IJ.showProgress((double)globalProgress[0] / updates);
				}
			}
		}
	}

	public static void main( String[] args )
	{
		new ImageJ();
		
		// test blending
		ImageFactory< FloatType > f = new ImageFactory<FloatType>( new FloatType(), new ArrayContainerFactory() );
		Image< FloatType > img = f.createImage( new int[] { 400, 400 } ); 
		
		LocalizableCursor< FloatType > c = img.createLocalizableCursor();
		final int numDimensions = img.getNumDimensions();
		final float[] tmp = new float[ numDimensions ];
		
		// for blending
		final int[] dimensions = img.getDimensions();
		final float percentScaling = 0.2f;
		final float[] border = new float[ numDimensions ];
					
		while ( c.hasNext() )
		{
			c.fwd();
			
			for ( int d = 0; d < numDimensions; ++d )
				tmp[ d ] = c.getPosition( d );
			
			c.getType().set( (float)BlendingPixelFusion.computeWeight( tmp, dimensions, border, percentScaling ) );
		}
		
		ImageJFunctions.show( img );
		System.out.println( "done" );
	}
}
