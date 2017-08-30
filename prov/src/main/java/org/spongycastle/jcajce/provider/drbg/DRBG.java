package org.spongycastle.jcajce.provider.drbg;

import java.security.AccessController;
import java.security.PrivilegedAction;
import java.security.Provider;
import java.security.SecureRandom;
import java.security.SecureRandomSpi;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.spongycastle.crypto.digests.SHA512Digest;
import org.spongycastle.crypto.macs.HMac;
import org.spongycastle.crypto.prng.EntropySource;
import org.spongycastle.crypto.prng.EntropySourceProvider;
import org.spongycastle.crypto.prng.SP800SecureRandom;
import org.spongycastle.crypto.prng.SP800SecureRandomBuilder;
import org.spongycastle.jcajce.provider.config.ConfigurableProvider;
import org.spongycastle.jcajce.provider.symmetric.util.ClassUtil;
import org.spongycastle.jcajce.provider.util.AsymmetricAlgorithmProvider;
import org.spongycastle.util.Arrays;
import org.spongycastle.util.Pack;
import org.spongycastle.util.Strings;

public class DRBG
{
    private static final String PREFIX = DRBG.class.getName();

    // {"Provider class name","SecureRandomSpi class name"}
    private static final String[][] initialEntropySourceNames = new String[][]
        {
            // Normal JVM
            {"sun.security.provider.Sun", "sun.security.provider.SecureRandom"},
            // Apache harmony
            {"org.apache.harmony.security.provider.crypto.CryptoProvider", "org.apache.harmony.security.provider.crypto.SHA1PRNG_SecureRandomImpl"},
            // Android.
            {"com.android.org.conscrypt.OpenSSLProvider", "com.android.org.conscrypt.OpenSSLRandom"},
            {"org.conscrypt.OpenSSLProvider", "org.conscrypt.OpenSSLRandom"},
        };

    private static final Object[] initialEntropySourceAndSpi = findSource();

    // Cascade through providers looking for match.
    private final static Object[] findSource()
    {
        for (int t = 0; t < initialEntropySourceNames.length; t++)
        {
            String[] pair = initialEntropySourceNames[t];
            try
            {
                Object[] r = new Object[]{Class.forName(pair[0]).newInstance(), Class.forName(pair[1]).newInstance()};

                return r;
            }
            catch (Throwable ex)
            {
                continue;
            }
        }

        return null;
    }

    private static class CoreSecureRandom
        extends SecureRandom
    {
        CoreSecureRandom()
        {
            super((SecureRandomSpi)initialEntropySourceAndSpi[1], (Provider)initialEntropySourceAndSpi[0]);
        }
    }

    // unfortunately new SecureRandom() can cause a regress and it's the only reliable way of getting access
    // to the JVM's seed generator.
    private static SecureRandom createInitialEntropySource()
    {
        if (initialEntropySourceAndSpi != null)
        {
            return new CoreSecureRandom();
        }
        else
        {
            return new SecureRandom();  // we're desperate, it's worth a try.
        }
    }

    private static EntropySourceProvider createEntropySource()
    {
        final String sourceClass = System.getProperty("org.spongycastle.drbg.entropysource");

        return AccessController.doPrivileged(new PrivilegedAction<EntropySourceProvider>()
        {
            public EntropySourceProvider run()
            {
                try
                {
                    Class clazz = ClassUtil.loadClass(DRBG.class, sourceClass);

                    return (EntropySourceProvider)clazz.newInstance();
                }
                catch (Exception e)
                {
                    throw new IllegalStateException("entropy source " + sourceClass + " not created: " + e.getMessage(), e);
                }
            }
        });
    }

    private static SecureRandom createBaseRandom(boolean isPredictionResistant)
    {
        if (System.getProperty("org.spongycastle.drbg.entropysource") != null)
        {
            EntropySourceProvider entropyProvider = createEntropySource();

            EntropySource initSource = entropyProvider.get(16 * 8);

            byte[] personalisationString = isPredictionResistant ? generateDefaultPersonalizationString(initSource.getEntropy())
                                                                 : generateNonceIVPersonalizationString(initSource.getEntropy());

            return new SP800SecureRandomBuilder(entropyProvider)
                                .setPersonalizationString(personalisationString)
                                .buildHash(new SHA512Digest(), Arrays.concatenate(initSource.getEntropy(), initSource.getEntropy()), isPredictionResistant);
        }
        else
        {
            SecureRandom randomSource = new HybridSecureRandom();   // needs to be done late, can't use static

            byte[] personalisationString = isPredictionResistant ? generateDefaultPersonalizationString(randomSource.generateSeed(16))
                                                                 : generateNonceIVPersonalizationString(randomSource.generateSeed(16));

            return new SP800SecureRandomBuilder(randomSource, true)
                .setPersonalizationString(personalisationString)
                .buildHash(new SHA512Digest(), randomSource.generateSeed(32), isPredictionResistant);
        }
    }

    public static class Default
        extends SecureRandomSpi
    {
        private static final SecureRandom random = createBaseRandom(true);

        public Default()
        {
        }

        protected void engineSetSeed(byte[] bytes)
        {
            random.setSeed(bytes);
        }

        protected void engineNextBytes(byte[] bytes)
        {
            random.nextBytes(bytes);
        }

        protected byte[] engineGenerateSeed(int numBytes)
        {
            return random.generateSeed(numBytes);
        }
    }

    public static class NonceAndIV
        extends SecureRandomSpi
    {
        private static final SecureRandom random = createBaseRandom(false);

        public NonceAndIV()
        {
        }

        protected void engineSetSeed(byte[] bytes)
        {
            random.setSeed(bytes);
        }

        protected void engineNextBytes(byte[] bytes)
        {
            random.nextBytes(bytes);
        }

        protected byte[] engineGenerateSeed(int numBytes)
        {
            return random.generateSeed(numBytes);
        }
    }

    public static class Mappings
        extends AsymmetricAlgorithmProvider
    {
        public Mappings()
        {
        }

        public void configure(ConfigurableProvider provider)
        {
            provider.addAlgorithm("SecureRandom.DEFAULT", PREFIX + "$Default");
            provider.addAlgorithm("SecureRandom.NONCEANDIV", PREFIX + "$NonceAndIV");
        }
    }

    private static byte[] generateDefaultPersonalizationString(byte[] seed)
    {
        return Arrays.concatenate(Strings.toByteArray("Default"), seed,
            Pack.longToBigEndian(Thread.currentThread().getId()), Pack.longToBigEndian(System.currentTimeMillis()));
    }

    private static byte[] generateNonceIVPersonalizationString(byte[] seed)
    {
        return Arrays.concatenate(Strings.toByteArray("Nonce"), seed,
            Pack.longToLittleEndian(Thread.currentThread().getId()), Pack.longToLittleEndian(System.currentTimeMillis()));
    }

    private static class HybridSecureRandom
        extends SecureRandom
    {
        private final AtomicBoolean seedAvailable = new AtomicBoolean(false);
        private final AtomicInteger samples = new AtomicInteger(0);
        private final SecureRandom baseRandom = createInitialEntropySource();

        private final SP800SecureRandom drbg;

        HybridSecureRandom()
        {
            super(null, null);
            drbg = new SP800SecureRandomBuilder(new EntropySourceProvider()
                {
                    public EntropySource get(final int bitsRequired)
                    {
                        return new SignallingEntropySource(bitsRequired);
                    }
                })
                .setPersonalizationString(Strings.toByteArray("Bouncy Castle Hybrid Entropy Source"))
                .buildHMAC(new HMac(new SHA512Digest()), baseRandom.generateSeed(32), false);     // 32 byte nonce
        }

        public void setSeed(byte[] seed)
        {
            if (drbg != null)
            {
                drbg.setSeed(seed);
            }
        }

        public void setSeed(long seed)
        {
            if (drbg != null)
            {
                drbg.setSeed(seed);
            }
        }

        public byte[] generateSeed(int numBytes)
        {
            byte[] data = new byte[numBytes];

            // after 20 samples we'll start to check if there is new seed material.
            if (samples.getAndIncrement() > 20)
            {
                if (seedAvailable.getAndSet(false))
                {
                    samples.set(0);
                    drbg.reseed(null);
                }
            }

            drbg.nextBytes(data);

            return data;
        }

        private class SignallingEntropySource
            implements EntropySource
        {
            private final int byteLength;
            private final AtomicReference entropy = new AtomicReference();
            private final AtomicBoolean scheduled = new AtomicBoolean(false);

            SignallingEntropySource(int bitsRequired)
            {
                this.byteLength = (bitsRequired + 7) / 8;
            }

            public boolean isPredictionResistant()
            {
                return true;
            }

            public byte[] getEntropy()
            {
                byte[] seed = (byte[])entropy.getAndSet(null);

                if (seed == null || seed.length != byteLength)
                {
                    seed = baseRandom.generateSeed(byteLength);
                }
                else
                {
                    scheduled.set(false);
                }

                if (!scheduled.getAndSet(true))
                {
                    new Thread(new EntropyGatherer(byteLength)).start();
                }

                return seed;
            }

            public int entropySize()
            {
                return byteLength * 8;
            }

            private class EntropyGatherer
                implements Runnable
            {
                private final int numBytes;

                EntropyGatherer(int numBytes)
                {
                    this.numBytes = numBytes;
                }

                public void run()
                {
                    entropy.set(baseRandom.generateSeed(numBytes));
                    seedAvailable.set(true);
                }
            }
        }
    }
}
