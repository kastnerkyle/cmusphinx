#include <math.h>
#include "cdcn.h"


/************************************************************************
 *   Dummy routine to convert from suitcase to sane varibles
 ***************************************************************************/

void block_cdcn_norm (float z[][NUM_COEFF+1],  /* The input cepstrum */
		      int num_frames,          /* Number of frames in utterance */
		      CDCN_type *cdcn_variables)
{
    /* Multidimensional arrays in C suck, so we have to
       forward-declare-hack this. */
    static void block_actual_cdcn_norm();
    float *variance, *prob, *tilt, *noise, *codebook, *corrbook;
    int    num_codes;

    /*
     * If error, dont bother
     */
    if (!cdcn_variables->run_cdcn)
        return;

    /*
     * If the variables haven't been intialized, dont normalize
     * else results may be diastrous
     */
    if (cdcn_variables->firstcall)
        return;

    /*
     * Open suitcase
     */

    variance	= cdcn_variables->variance;
    prob	= cdcn_variables->probs;
    tilt	= cdcn_variables->tilt;
    noise	= cdcn_variables->noise;
    codebook	= cdcn_variables->means;
    corrbook	= cdcn_variables->corrbook;
    num_codes	= cdcn_variables->num_codes;

    block_actual_cdcn_norm(variance, prob, tilt, noise, codebook, 
			   corrbook, num_codes, z, num_frames);
    return;
}

/*************************************************************************
 *
 * cdcn_norm finds the cepstrum vectors x for the whole utterance that minimize
 * the squared error.
 * This routines cleans up a block of data
 * Coded by Alex Acero (acero@s),  November 1989 
 *
 *************************************************************************/

static void
block_actual_cdcn_norm(float variance[][NUM_COEFF+1], /* Speech cepstral variances of modes */
		       float *prob,  /* Ratio of a-prori mode probs. to mod variance */
		       float *tilt,  /* Spectral tilt cepstrum */
		       float *noise, /* Noise estimate */
		       float means[][NUM_COEFF+1], /* The cepstrum codebook */
		       float corrbook[][NUM_COEFF+1], /* The correction factor's codebook */
		       int num_codes, /* Number of codewords in codebook */
		       float z[][NUM_COEFF+1], /* The input cepstrum */
		       int num_frames) /* Number of frames in utterance */
{
    float       distance,  /* distance value */
                den,       /* Denominator for reestimation */
                fk,        /* Probabilities for different codewords */
                difference;     /* stores z -x -q -r  */
    int         i,              /* Index frames in utterance */
                j,              /* Index coefficients within frame */
                k;              /* Index codewords in codebook */


    float x[NUM_COEFF+1];

    /* Reestimate x vector for all frames in utterance */
    for (i = 0; i < num_frames; i++) 
    {
        /* Initialize cleaned vector x */
        for (j = 0; j <= NUM_COEFF; j++)
            x[j] = 0.0;

	difference = z[i][0] - means[0][0] - corrbook[0][0] - tilt[0];
	distance = difference*difference / variance[0][0];
        for (j = 1; j <= NUM_COEFF; j++)
        {
	    difference = z[i][j] - tilt[j] - means[0][j] - corrbook[0][j];	
	    distance += difference*difference / variance[0][j];
        }
        fk = exp ((double) - distance / 2) * prob[0];
        den = fk;

        /* Reestimate vector x across all codewords */
        for (k = 1; k < num_codes; k++) 
        {
            /* Find estimated vector for codeword k and update x */
	    difference = z[i][0] - means[k][0] - corrbook[k][0] - tilt[0];
	    distance = difference*difference / variance[k][0]; 	
            for (j = 1; j <= NUM_COEFF; j++)
	    {
		difference = z[i][j] - tilt[j] - corrbook[k][j] - means[k][j];
		distance += difference*difference / variance[k][j];
	    }
            fk = exp ((double) - distance / 2) * prob[k];
            for (j = 0; j <= NUM_COEFF; j++)
                x[j] += (z[i][j] - tilt[j] - corrbook[k][j]) * fk;
            den += fk;
        }

        /* Normalize the estimated x vector across codewords 
         * The if test is only for sanity. It almost never fails 
         */
	if (den != 0)
            for (j = 0; j <= NUM_COEFF; j++)
                z[i][j] = x[j]/den;
        else
           z[i][j] -= tilt[j];

        /* 
         * z[][] itself carries the cleaned speech now
         */
    }
}
