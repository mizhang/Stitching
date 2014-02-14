package mpicbg.stitching.fusion;

import net.imglib2.RealRandomAccess;
import net.imglib2.img.Img;
import net.imglib2.interpolation.InterpolatorFactory;
import net.imglib2.type.numeric.RealType;

/**
 * This class is necessary as it can create a {@link RealRandomAccess} for an {@link Img} even if hold it as < ? extends RealType< ? > >
 * 
 * @author preibischs
 *
 * @param <T>
 */
public class ImageInterpolation< T extends RealType< T > > 
{
	final Img< T > image;
	final InterpolatorFactory< T, T > interpolatorFactory;
	
	public ImageInterpolation( final Img< T > image, final InterpolatorFactory< T, T > interpolatorFactory )
	{
		this.image = image;
		this.interpolatorFactory = interpolatorFactory;
	}
	
	public Img< T > getImage() { return image; }
	public RealRandomAccess< T > createRealRandomAccess() { return interpolatorFactory.create( image.firstElement(), image ); }
}
