package mpicbg.stitching;

import net.imglib2.algorithm.fft.PhaseCorrelationPeak;
import net.imglib2.algorithm.scalespace.DifferenceOfGaussian.SpecialPoint;
import net.imglib2.algorithm.scalespace.DifferenceOfGaussianPeak;
import net.imglib2.type.numeric.real.FloatType;

public class Peak extends DifferenceOfGaussianPeak<FloatType> 
{
	final PhaseCorrelationPeak peak;

	public Peak( final PhaseCorrelationPeak peak )
	{
		super( peak.getOriginalInvPCMPosition(), new FloatType( peak.getPhaseCorrelationPeak() ), SpecialPoint.MAX );

		this.peak = peak;
	}
	
	public PhaseCorrelationPeak getPCPeak() { return peak; }

}
