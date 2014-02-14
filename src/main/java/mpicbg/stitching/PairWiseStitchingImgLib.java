package mpicbg.stitching;

import fiji.stacks.Hyperstack_rearranger;
import ij.IJ;
import ij.ImagePlus;
import ij.gui.Roi;

import java.util.ArrayList;
import java.util.Vector;
import java.util.concurrent.atomic.AtomicInteger;

import net.imglib2.Cursor;
import net.imglib2.RandomAccess;
import net.imglib2.algorithm.legacy.fft.PhaseCorrelation;
import net.imglib2.algorithm.legacy.fft.PhaseCorrelationPeak;
import net.imglib2.algorithm.legacy.scalespace.DifferenceOfGaussianPeak;
import net.imglib2.algorithm.legacy.scalespace.SubpixelLocalization;
import net.imglib2.img.Img;
import net.imglib2.img.ImgFactory;
import net.imglib2.img.array.ArrayImgFactory;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.multithreading.Chunk;
import net.imglib2.multithreading.SimpleMultiThreading;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.integer.UnsignedByteType;
import net.imglib2.type.numeric.integer.UnsignedShortType;
import net.imglib2.type.numeric.real.FloatType;

/**
 * Pairwise Stitching of two ImagePlus using ImgLib1 and PhaseCorrelation.
 * It deals with aligning two slices (2d) or stacks (3d) having an arbitrary
 * amount of channels. If the ImagePlus contains several time-points it will 
 * only consider the first time-point as this requires global optimization of 
 * many independent 2d/3d <-> 2d/3d alignments.
 * 
 * @author Stephan Preibisch (stephan.preibisch@gmx.de)
 *
 */
public class PairWiseStitchingImgLib 
{
	public static PairWiseStitchingResult stitchPairwise( final ImagePlus imp1, final ImagePlus imp2, Roi roi1, Roi roi2, final int timepoint1, final int timepoint2, final StitchingParameters params )
	{
		PairWiseStitchingResult result = null;
		roi1 = getOnlyRectangularRoi( roi1 );
		roi2 = getOnlyRectangularRoi( roi2 );
		
		// can both images be wrapped into imglib without copying
		final boolean canWrap = !StitchingParameters.alwaysCopy && canWrapIntoImgLib( imp1, roi1, params.channel1 ) && canWrapIntoImgLib( imp2, roi2, params.channel2 );
		
		
		//
		// the ugly but correct way into generic programming...
		//
		if ( canWrap )
		{
			if ( imp1.getType() == ImagePlus.GRAY32 )
			{
				final Img<FloatType> image1 = getWrappedImageFloat( imp1, params.channel1, timepoint1 );
				
				if ( imp2.getType() == ImagePlus.GRAY32 )
					result = performStitching( image1, getWrappedImageFloat( imp2, params.channel2, timepoint2 ), params );
				else if ( imp2.getType() == ImagePlus.GRAY16 )
					result = performStitching( image1, getWrappedImageUnsignedShort( imp2, params.channel2, timepoint2 ), params );
				else if ( imp2.getType() == ImagePlus.GRAY8 )
					result = performStitching( image1, getWrappedImageUnsignedByte( imp2, params.channel2, timepoint2 ), params );
				else
					IJ.log( "Unknown image type: " + imp2.getType() );
			}
			else if ( imp1.getType() == ImagePlus.GRAY16 )
			{
				final Img<UnsignedShortType> image1 = getWrappedImageUnsignedShort( imp1, params.channel1, timepoint1 );
				
				if ( imp2.getType() == ImagePlus.GRAY32 )
					result = performStitching( image1, getWrappedImageFloat( imp2, params.channel2, timepoint2 ), params );
				else if ( imp2.getType() == ImagePlus.GRAY16 )
					result = performStitching( image1, getWrappedImageUnsignedShort( imp2, params.channel2, timepoint2 ), params );
				else if ( imp2.getType() == ImagePlus.GRAY8 )
					result = performStitching( image1, getWrappedImageUnsignedByte( imp2, params.channel2, timepoint2 ), params );
				else
					IJ.log( "Unknown image type: " + imp2.getType() );
			} 
			else if ( imp1.getType() == ImagePlus.GRAY8 )
			{
				final Img<UnsignedByteType> image1 = getWrappedImageUnsignedByte( imp1, params.channel1, timepoint1 );
				
				if ( imp2.getType() == ImagePlus.GRAY32 )
					result = performStitching( image1, getWrappedImageFloat( imp2, params.channel2, timepoint2 ), params );
				else if ( imp2.getType() == ImagePlus.GRAY16 )
					result = performStitching( image1, getWrappedImageUnsignedShort( imp2, params.channel2, timepoint2 ), params );
				else if ( imp2.getType() == ImagePlus.GRAY8 )
					result = performStitching( image1, getWrappedImageUnsignedByte( imp2, params.channel2, timepoint2 ), params );
				else
					IJ.log( "Unknown image type: " + imp2.getType() );
			} 
			else
			{
				IJ.log( "Unknown image type: " + imp1.getType() );			
			}
		}
		else
		{
			final ImgFactory<UnsignedByteType> imgFactoryByte = new ArrayImgFactory<UnsignedByteType>( );
			final ImgFactory<UnsignedShortType> imgFactoryShort = new ArrayImgFactory<UnsignedShortType>( );
			final ImgFactory<FloatType> imgFactoryFloat = new ArrayImgFactory<FloatType>( );
			
			if ( imp1.getType() == ImagePlus.GRAY32 )
			{
				final Img< FloatType > image1 = getImage( imp1, roi1, imgFactoryFloat, new FloatType(), params.channel1, timepoint1 );
				
				if ( imp2.getType() == ImagePlus.GRAY32 )
					result = performStitching( image1, getImage( imp2, roi2, imgFactoryFloat, new FloatType(), params.channel2, timepoint2 ), params );
				else if ( imp2.getType() == ImagePlus.GRAY16 )
					result = performStitching( image1, getImage( imp2, roi2, imgFactoryShort, new UnsignedShortType(), params.channel2, timepoint2 ), params );
				else if ( imp2.getType() == ImagePlus.GRAY8 )
					result = performStitching( image1, getImage( imp2, roi2, imgFactoryByte, new UnsignedByteType(), params.channel2, timepoint2 ), params );
				else
					IJ.log( "Unknown image type: " + imp2.getType() );					
			}
			else if ( imp1.getType() == ImagePlus.GRAY16 )
			{
				final Img< UnsignedShortType > image1 = getImage( imp1, roi1, imgFactoryShort, new UnsignedShortType(), params.channel1, timepoint1 );
				
				if ( imp2.getType() == ImagePlus.GRAY32 )
					result = performStitching( image1, getImage( imp2, roi2, imgFactoryFloat, new FloatType(), params.channel2, timepoint2 ), params );
				else if ( imp2.getType() == ImagePlus.GRAY16 )
					result = performStitching( image1, getImage( imp2, roi2, imgFactoryShort, new UnsignedShortType(), params.channel2, timepoint2 ), params );
				else if ( imp2.getType() == ImagePlus.GRAY8 )
					result = performStitching( image1, getImage( imp2, roi2, imgFactoryByte, new UnsignedByteType(), params.channel2, timepoint2 ), params );
				else
					IJ.log( "Unknown image type: " + imp2.getType() );					
			}
			else if ( imp1.getType() == ImagePlus.GRAY8 )
			{
				final Img< UnsignedByteType > image1 = getImage( imp1, roi1, imgFactoryByte, new UnsignedByteType(), params.channel1, timepoint1 );
				
				if ( imp2.getType() == ImagePlus.GRAY32 )
					result = performStitching( image1, getImage( imp2, roi2, imgFactoryFloat, new FloatType(), params.channel2, timepoint2 ), params );
				else if ( imp2.getType() == ImagePlus.GRAY16 )
					result = performStitching( image1, getImage( imp2, roi2, imgFactoryShort, new UnsignedShortType(), params.channel2, timepoint2 ), params );
				else if ( imp2.getType() == ImagePlus.GRAY8 )
					result = performStitching( image1, getImage( imp2, roi2, imgFactoryByte, new UnsignedByteType(), params.channel2, timepoint2 ), params );
				else
					IJ.log( "Unknown image type: " + imp2.getType() );					
			}
			else
			{
				IJ.log( "Unknown image type: " + imp1.getType() );			
			}
		}
		if ( result == null )
		{
			IJ.log( "Pairwise stitching failed." );
			return null;
		}
		
		// add the offset to the shift
		if ( roi2 != null )
		{
			result.offset[ 0 ] -= roi2.getBounds().x;
			result.offset[ 1 ] -= roi2.getBounds().y;
		}	
		
		if ( roi1 != null )
		{
			result.offset[ 0 ] += roi1.getBounds().x;
			result.offset[ 1 ] += roi1.getBounds().y;			
		}
		
		return result;
	}

	public static < T extends RealType<T>, S extends RealType<S> > PairWiseStitchingResult performStitching( final Img<T> img1, final Img<S> img2, final StitchingParameters params )
	{
		if ( img1 == null )
		{
			IJ.log( "Image 1 could not be wrapped." );
			return null;
		}
		else if ( img2 == null )
		{
			IJ.log( "Image 2 could not be wrapped." );
			return null;
		}
		else if ( params == null )
		{
			IJ.log( "Parameters are null." );
			return null;
		}
		
		final PairWiseStitchingResult result = computePhaseCorrelation( img1, img2, params.checkPeaks, params.subpixelAccuracy );
		
		return result;
	}
	
	public static < T extends RealType<T>, S extends RealType<S> > PairWiseStitchingResult computePhaseCorrelation( final Img<T> img1, final Img<S> img2, final int numPeaks, final boolean subpixelAccuracy )
	{
		final PhaseCorrelation< T, S > phaseCorr = new PhaseCorrelation<T, S>( img1, img2 );
		phaseCorr.setInvestigateNumPeaks( numPeaks );
		
		if ( subpixelAccuracy )
			phaseCorr.setKeepPCM( true );
		
		phaseCorr.setComputeFFTinParalell( true );
		if ( !phaseCorr.process() )
		{
			IJ.log( "Could not compute phase correlation: " + phaseCorr.getErrorMessage() );
			return null;
		}

		// result
		final PhaseCorrelationPeak pcp = phaseCorr.getShift();
		final float[] shift = new float[ img1.numDimensions() ];
		final PairWiseStitchingResult result;
		
		if ( subpixelAccuracy )
		{
			final Img<FloatType> pcm = phaseCorr.getPCM();		
		
			final ArrayList<DifferenceOfGaussianPeak<FloatType>> list = new ArrayList<DifferenceOfGaussianPeak<FloatType>>();		
			final Peak p = new Peak( pcp );
			list.add( p );
					
			final SubpixelLocalization<FloatType> spl = new SubpixelLocalization<FloatType>( pcm, list );
			final boolean move[] = new boolean[ pcm.numDimensions() ];
			for ( int i = 0; i < pcm.numDimensions(); ++i )
				move[ i ] = false;
			spl.setCanMoveOutside( true );
			spl.setAllowedToMoveInDim( move );
			spl.setMaxNumMoves( 0 );
			spl.setAllowMaximaTolerance( false );
			spl.process();
			
			final Peak peak = (Peak)list.get( 0 );
			
			for ( int d = 0; d < img1.numDimensions(); ++d )
				shift[ d ] = peak.getPCPeak().getPosition()[ d ] + peak.getSubPixelPositionOffset( d );
			
			result = new PairWiseStitchingResult( shift, pcp.getCrossCorrelationPeak(), p.getValue().get() );
		}
		else
		{
			for ( int d = 0; d < img1.numDimensions(); ++d )
				shift[ d ] = pcp.getPosition()[ d ];
			
			result = new PairWiseStitchingResult( shift, pcp.getCrossCorrelationPeak(), pcp.getPhaseCorrelationPeak() );
		}
		
		return result;
	}

	/**
	 * return an {@link Img}<T> as input for the PhaseCorrelation.
	 * 
	 * @param imp - the {@link ImagePlus}
	 * @param imgFactory - the {@link ImgFactory} defining wher to put it into
	 * @param targetType - which {@link RealType}
	 * @param channel - which channel (if channel=0 means average all channels)
	 * @param timepoint - which timepoint
	 * 
	 * @return - the {@link Img} or null if it was not an ImagePlus.GRAY8, ImagePlus.GRAY16 or ImagePlus.GRAY32
	 */
	public static < T extends RealType<T> > Img<T> getImage( final ImagePlus imp, Roi roi, final ImgFactory<T> imgFactory, final T targetType, final int channel, final int timepoint )
	{
		// first test the roi
		roi = getOnlyRectangularRoi( roi );
		
		// how many dimensions?
		final int numDimensions;		
		if ( imp.getNSlices() > 1 )
			numDimensions = 3;
		else
			numDimensions = 2;
		
		// the size of the image
		final int[] size = new int[ numDimensions ];
		final int[] offset = new int[ numDimensions ];
		
		if ( roi == null )
		{
			size[ 0 ] = imp.getWidth();
			size[ 1 ] = imp.getHeight();
			
			if ( numDimensions == 3 )
				size[ 2 ] = imp.getNSlices();
		}
		else
		{
			size[ 0 ] = roi.getBounds().width;
			size[ 1 ] = roi.getBounds().height;

			offset[ 0 ] = roi.getBounds().x;
			offset[ 1 ] = roi.getBounds().y;
			
			if ( numDimensions == 3 )
				size[ 2 ] = imp.getNSlices();
		}
		
		// create the Image
		final Img<T> img = imgFactory.create( size, targetType );
		final boolean success;
		
		// copy the content
		if ( channel == 0 )
		{
			// we need to average all channels
			success = averageAllChannels( img, offset, imp, timepoint );
		}
		else
		{
			// otherwise only copy one channel
			success = fillInChannel( img, offset, imp, channel, timepoint );
		}
		
		if ( success )
		{
			return img;
		}
		return null;
	}
	
	/**
	 * Averages all channels into the target image. The size is given by the dimensions of the target image,
	 * the offset (if applicable) is given by an extra field
	 * 
	 * @param target - the target Image
	 * @param offset - the offset of the area (might be [0,0] or [0,0,0])
	 * @param imp - the input ImagePlus
	 * @param timepoint - for which timepoint
	 * 
	 * @return true if successful, false if the ImagePlus type was unknow
	 */
	public static < T extends RealType< T > > boolean averageAllChannels( final Img< T > target, final int[] offset, final ImagePlus imp, final int timepoint )
	{
		final int numChannels = imp.getNChannels();
		
		if ( imp.getType() == ImagePlus.GRAY8 )
		{
			final ArrayList< Img< UnsignedByteType > > images = new ArrayList<Img<UnsignedByteType>>();

			// first get wrapped instances of all channels
			for ( int c = 1; c <= numChannels; ++c )
				images.add( getWrappedImageUnsignedByte( imp, c, timepoint ) );			

			averageAllChannels( target, images, offset );			
			return true;
		}
		else if ( imp.getType() == ImagePlus.GRAY16 )
		{
			final ArrayList< Img< UnsignedShortType > > images = new ArrayList<Img<UnsignedShortType>>();

			// first get wrapped instances of all channels
			for ( int c = 1; c <= numChannels; ++c )
				images.add( getWrappedImageUnsignedShort( imp, c, timepoint ) );			

			averageAllChannels( target, images, offset );
			return true;
		}
		else if ( imp.getType() == ImagePlus.GRAY32 )
		{
			final ArrayList< Img< FloatType > > images = new ArrayList<Img<FloatType>>();

			// first get wrapped instances of all channels
			for ( int c = 1; c <= numChannels; ++c )
				images.add( getWrappedImageFloat( imp, c, timepoint ) );
			
			averageAllChannels( target, images, offset );
			return true;
		}
		else
		{
			IJ.log( "Unknow image type: " + imp.getType() );
			return false;
		}
	}

	/**
	 * Averages all channels into the target image. The size is given by the dimensions of the target image,
	 * the offset (if applicable) is given by an extra field
	 * 
	 * @param target - the target Img
	 * @param offset - the offset of the area (might be [0,0] or [0,0,0])
	 * @param imp - the input ImagePlus
	 * @param timepoint - for which timepoint
	 * 
	 * @return true if successful, false if the ImagePlus type was unknow
	 */
	public static < T extends RealType< T > > boolean fillInChannel( final Img< T > target, final int[] offset, final ImagePlus imp, final int channel, final int timepoint )
	{
		if ( imp.getType() == ImagePlus.GRAY8 )
		{
			final ArrayList< Img< UnsignedByteType > > images = new ArrayList<Img<UnsignedByteType>>();

			// first get wrapped instances of all channels
			images.add( getWrappedImageUnsignedByte( imp, channel, timepoint ) );			

			averageAllChannels( target, images, offset );			
			return true;
		}
		else if ( imp.getType() == ImagePlus.GRAY16 )
		{
			final ArrayList< Img< UnsignedShortType > > images = new ArrayList<Img<UnsignedShortType>>();

			// first get wrapped instances of all channels
			images.add( getWrappedImageUnsignedShort( imp, channel, timepoint ) );			

			averageAllChannels( target, images, offset );
			return true;
		}
		else if ( imp.getType() == ImagePlus.GRAY32 )
		{
			final ArrayList< Img< FloatType > > images = new ArrayList<Img<FloatType>>();

			// first get wrapped instances of all channels
			images.add( getWrappedImageFloat( imp, channel, timepoint ) );
			
			averageAllChannels( target, images, offset );
			return true;
		}
		else
		{
			IJ.log( "Unknow image type: " + imp.getType() );
			return false;
		}
	}

	/**
	 * Averages all channels into the target image. The size is given by the dimensions of the target image,
	 * the offset (if applicable) is given by an extra field
	 * 
	 * @param target - the target Img
	 * @param offset - the offset of the area (might be [0,0] or [0,0,0])
	 * @param sources - a list of input Images
	 */
	protected static < T extends RealType< T >, S extends RealType< S > > void averageAllChannels( final Img< T > target, final ArrayList< Img< S > > sources, final int[] offset )
	{
		// get the major numbers
		final int numDimensions = target.numDimensions();
		final float numImages = sources.size();
		long imageSize = target.dimension( 0 );
		
		for ( int d = 1; d < target.numDimensions(); ++d )
			imageSize *= target.dimension( d );

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
                	
            		// the cursor for the output
            		final Cursor< T > targetCursor =  target.localizingCursor();
            		
            		// the input cursors
            		final ArrayList< RandomAccess< S > > sourceCursors = new ArrayList< RandomAccess< S > > ();
            		
            		for ( final Img< S > source : sources )
            			sourceCursors.add( source.randomAccess() );
            		
            		// temporary array
            		final long[] location = new long[ numDimensions ]; 

            		// move to the starting position of the current thread
            		targetCursor.jumpFwd( startPos );
                    
            		// do as many pixels as wanted by this thread
                    for ( long j = 0; j < loopSize; ++j )
            		{
            			targetCursor.fwd();
            			targetCursor.localize( location );
            			
            			for ( int d = 0; d < numDimensions; ++d )
            				location[ d ] += offset[ d ];
            			
            			float sum = 0;
            			
            			for ( final RandomAccess< S > sourceCursor : sourceCursors )
            			{
            				sourceCursor.setPosition( location );
            				sum += sourceCursor.get().getRealFloat();
            			}
            			
            			targetCursor.get().setReal( sum / numImages );
            		}                	
                }
            });
        
        SimpleMultiThreading.startAndJoin( threads );		
	}

	/**
	 * return an {@link Img} of {@link UnsignedByteType} as input for the PhaseCorrelation. If no rectangular roi
	 * is selected, it will only wrap the existing ImagePlus!
	 * 
	 * @param targetType - which {@link RealType}
	 * @param channel - which channel (if channel=0 means average all channels)
	 * @param timepoint - which timepoint
	 * @param imp - the {@link ImagePlus}
	 * 
	 * @return - the {@link Img} or null if it was not an ImagePlus.GRAY8 or if channel = 0
	 */
	public static Img<UnsignedByteType> getWrappedImageUnsignedByte( final ImagePlus imp, final int channel, final int timepoint )
	{
		if ( channel == 0 || imp.getType() != ImagePlus.GRAY8 )
			return null;
		return ImageJFunctions.wrapByte( Hyperstack_rearranger.getImageChunk( imp, channel, timepoint ) );
	}

	/**
	 * return an {@link Img} of {@link UnsignedShortType} as input for the PhaseCorrelation. If no rectangular roi
	 * is selected, it will only wrap the existing ImagePlus!
	 * 
	 * @param targetType - which {@link RealType}
	 * @param channel - which channel (if channel=0 means average all channels)
	 * @param timepoint - which timepoint
	 * @param imp - the {@link ImagePlus}
	 * 
	 * @return - the {@link Img} or null if it was not an ImagePlus.GRAY16 or if channel = 0
	 */
	public static Img<UnsignedShortType> getWrappedImageUnsignedShort( final ImagePlus imp, final int channel, final int timepoint )
	{
		if ( channel == 0 || imp.getType() != ImagePlus.GRAY16 )
			return null;
		return ImageJFunctions.wrapShort( Hyperstack_rearranger.getImageChunk( imp, channel, timepoint ) );
	}

	/**
	 * return an {@link Img} of {@link FloatType} as input for the PhaseCorrelation. If no rectangular roi
	 * is selected, it will only wrap the existing ImagePlus!
	 * 
	 * @param targetType - which {@link RealType}
	 * @param channel - which channel (if channel=0 means average all channels)
	 * @param timepoint - which timepoint
	 * @param imp - the {@link ImagePlus}
	 * 
	 * @return - the {@link Img} or null if it was not an ImagePlus.GRAY32 or if channel = 0
	 */
	public static Img<FloatType> getWrappedImageFloat( final ImagePlus imp, final int channel, final int timepoint )
	{
		if ( channel == 0 || imp.getType() != ImagePlus.GRAY32 )
			return null;
		return ImageJFunctions.wrapFloat( Hyperstack_rearranger.getImageChunk( imp, channel, timepoint ) );
	}

	/**
	 * Determines if this imageplus with these parameters can be wrapped directly into an Img<T>.
	 * This is important, because if we would wrap the first but not the second image, they would
	 * have different {@link ImgFactory}s
	 * 
	 * @param imp - the ImagePlus
	 * @param channel - which channel (if channel=0 means average all channels)
	 * 
	 * @return true if it can be wrapped, otherwise false
	 */
	public static boolean canWrapIntoImgLib( final ImagePlus imp, Roi roi, final int channel )
	{
		// first test the roi
		roi = getOnlyRectangularRoi( roi );
		
		return roi == null && channel > 0;
	}

	protected static Roi getOnlyRectangularRoi( Roi roi )
	{
		// we can only do rectangular rois
		if ( roi != null && roi.getType() != Roi.RECTANGLE )
			return null;
		return roi;
	}

	/*
	protected static Roi getOnlyRectangularRoi( final ImagePlus imp )
	{
		Roi roi = imp.getRoi();
		
		// we can only do rectangular rois
		if ( roi != null && roi.getType() != Roi.RECTANGLE )
		{
			IJ.log( "WARNING: roi for " + imp.getTitle() + " is not a rectangle, we have to ignore it." );
			roi = null;
		}

		return roi;
	}
	*/
}
