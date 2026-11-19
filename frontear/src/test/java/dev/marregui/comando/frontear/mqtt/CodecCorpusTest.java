package dev.marregui.comando.frontear.mqtt;

import java.io.ByteArrayInputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// The codec writes every packet of [EncodeCorpus] byte for byte as it did before it sized packets up front: each
/// packet's length and SHA-256 here are what the codec that grew a buffer and copied it wrote, and each packet reads
/// back to itself.
class CodecCorpusTest {

    /// Name, encoded length, SHA-256 of the encoded bytes, as the codec wrote them before it sized packets up front.
    private static final String[][] PREVIOUS = {
        {"connect-minimal", "16", "eb15d0ec5a5734a4c7d74127321837a96a231afe0f1fb9c92e7ccba440ff57e8"},
        {"connect-empty-client-id", "15", "be65a9c9c64c525b93d3dae747c40dca93ae268ce0a890be079d51633b874e27"},
        {"connect-all-properties", "37", "37d53cc7d6cf72f4dedcc41a3dfe99fb54cd44e709aefeec7ee951eb5aaf5441"},
        {"connect-will", "127", "f317f66ee870f5b8f9f36b87699a3d26ddd8312f8d186915fd844868871250eb"},
        {"connect-will-delay", "27", "377312155d17f10350ed8dacdf79fc36f06424dcb035d52728c1733186e0b176"},
        {"connack-default", "5", "6e517ac6a66a42bbca69f6dc0504696b566806116850cf4020338f7d0dc3408a"},
        {"connack-all-properties", "54", "e9ad2be9e906a6bf12ec331c85fba34a6d8d0c1c1e306d5c792c900ce4eb63d6"},
        {"connack-refused", "31", "b9e618c7fda2bddbd24053e17f535d2d422ef2f8d215b2cb5a604971774de346"},
        {"publish-qos0", "24", "4ad64b33cf32d9833864154f265e5142b8d84e807cc754ea2d5b55aa82f497c0"},
        {"publish-qos1-retain", "26", "4005a02c6a2d43609ef8763833b5e3453f53dc5da2166ea0e67422b5095e9763"},
        {"publish-qos2-dup", "26", "d4d1ab07832775a8e790bbc9309b4d3b4b88a4dbc93bf7167e62137bdfec1940"},
        {"publish-empty-payload", "6", "487e0e778caa85144ac04cdc3908758bdeeb301391536d97e0a964e0e42bf83e"},
        {"publish-all-properties", "210", "c3889266b14e7bc2ee0cecffc312a52d401e5c2210b161bb55a1ca6764317d68"},
        {"publish-empty-correlation", "29", "42c340ebe3b815154f6e343ceae5f3ec458763d9f2ddc072e0297d987f7a3510"},
        {"publish-remaining-127", "129", "8c198e2ac70dbfdd0cfc33676b00f242591c279e4c61a14ce622dd91adf0e14a"},
        {"publish-remaining-128", "131", "befe43082d0c6f395a9f4210500d19e8c19290a504323c3bac194d95d066b2b1"},
        {"publish-remaining-16383", "16386", "c065b7590420802ebe37426a424f93dfbf7196074baea43e86bdaf91389f9664"},
        {"publish-remaining-16384", "16388", "4293ebdb9d23e2b5fda71d1225a86562e132926cddfa3e05a6a49e18b3c1aaf0"},
        {"publish-remaining-2097151", "2097155", "d9c03e1a8b4986165f43e1fc5d180ba04f4f78ee7b7b5a9a020d7f01c0e35d2c"},
        {"publish-remaining-2097152", "2097157", "83b3ed95f256fe6bf32c560b8d44ea5c210b1b57a1f0eceda440688f6559fe74"},
        {"publish-section-127", "135", "6326027e643999596b43c2ba3ac14529969268ba022973d4959dda1320ac9324"},
        {"publish-section-128", "137", "30f6775f312c5f75d12667c6ad35bee9915b67d00c00721af83faf0b039ec7ac"},
        {"publish-section-16383", "16393", "774528ecbf43a833a5109b8580200bfe8e8f9a81b3a10d4121439375b87eb0df"},
        {"publish-section-16384", "16395", "8906d029a10b02974cc2a87ecadc833b5f19c71d41397e4d147a9c80b46aa9df"},
        {"publish-user-section-127", "135", "c7bfd0ef6af923a0ee3ee9dd83c08981c14eed050b13eae9ddff00fce7f0507a"},
        {"publish-user-section-128", "137", "dda63db6212046d8f1686a42e41fc3b419df1bd495e94259ac60448051714b6b"},
        {"publish-user-section-16383", "16393", "ece9f2ff1ed30c7d644625320aa5aee7067b97b35f2301932a8b225d6ea587d4"},
        {"publish-user-section-16384", "16395", "32903459e0264cfdd0b87ad7ba5cf8d3381e029b9d73f78beb5b1eb00de78624"},
        {"publish-longest-string", "65549", "18b58b463a4860587c73ba7b34ff13712fc2bd557f5490e52e6f689d2e4d7d09"},
        {"puback-success", "4", "5bc47f506b6fc13978889099c9b9a8fe1cad23ea6b92528b0144aa06a8e6d756"},
        {"puback-code", "5", "79766930fd7db529a42903d6a4ce2c9ab6ac2fadad28357d5357651f02c412c2"},
        {"puback-code-string", "27", "cac397ee6f91b97a0f82c33f517d4cafd5b7e55ac0d842fac595bf3982adddca"},
        {"puback-success-string", "11", "7fe3290024bcd42fdce92a3748b608c2c79b9cf3b78066b377018d88ff7e9286"},
        {"subscribe", "25", "c0c1e555f60b5850615751488ed3e3000b856ba182a24875a4a3e91c183a0d2c"},
        {"suback", "9", "e4143def7e4368f534228795b4fc66e05093b9f6a46a1fa5724b839c1ab011b1"},
        {"suback-string", "15", "c611f33adc20b9849e144d503faca12a8070333278a274cb79133d5fc9e28bd4"},
        {"unsubscribe", "20", "900a326eaac84c50d9e4e2c4842322e657ba9c4526877839c1236b2ee78d195a"},
        {"unsuback", "7", "b48d3586517e4dfc62b939d71f9c16b04bee799d5031954a5c20ec0099dc993c"},
        {"unsuback-string", "11", "505d0ffa2c9fa0fcb0127a7e28f6228393bbe717cfb7e6ae798a3bff582eaf9d"},
        {"pingreq", "2", "e9aff84fdb699ca706c0a1fed47bb095cb25e3c95aa5d1c5d216ff2cfbcd4998"},
        {"pingresp", "2", "683914de4c707d217d22e1fda1593473b74f71e71c4c2c67bf0dfdbfe0cb4fd3"},
        {"disconnect-success", "2", "be81fe4316df9b2c7b167f68bf1236a93ac7885211be33ebff7d638c095b2f15"},
        {"disconnect-code", "3", "e43f4733289ae3764313cbb64178c3ebe60a8f16e24d4c18ec572b027ef698fe"},
        {"disconnect-code-string", "17", "c3dbbfc0948a40c913a0dfa2fc2f02a39b1be9e2abf6399105bdc469ddfaa46a"},
        {"disconnect-session-expiry", "9", "8ac79f56bb863a519262a91c2b90aca27d70dbc1b4b4c3fc5b7dd37573386d58"},
        {"disconnect-session-expiry-string", "15", "66680cc972f20e138b5a080d2a12e7930475245d804f9d7f6b5a46a4422d9c49"},
    };

    /// Every corpus packet encodes to the previous bytes, the whole corpus and nothing else is covered, and each
    /// packet decodes back to itself.
    @Test
    void everyPacketEncodesAsBeforeAndReadsBack() throws Exception {
        final Map<String, String[]> previous = new LinkedHashMap<>();
        for (final String[] row : PREVIOUS) {
            previous.put(row[0], row);
        }
        final Map<String, Packet> corpus = EncodeCorpus.packets();
        assertThat(corpus.keySet()).containsExactlyElementsOf(previous.keySet());
        for (final Map.Entry<String, Packet> e : corpus.entrySet()) {
            final byte[] encoded = Codec.encode(e.getValue());
            final String[] row = previous.get(e.getKey());
            assertThat(encoded.length).as(e.getKey()).isEqualTo(Integer.parseInt(row[1]));
            assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(encoded)))
                    .as(e.getKey()).isEqualTo(row[2]);
            final ByteArrayInputStream in = new ByteArrayInputStream(encoded);
            assertThat(Codec.read(in, in.read(), Packet.PROTOCOL_MAXIMUM_PACKET_SIZE)).as(e.getKey())
                    .isEqualTo(e.getValue());
            assertThat(in.available()).as(e.getKey()).isZero();
        }
    }

    /// A PUBLISH of the corpus kept as a record reads back to itself, the record being the packet's bytes.
    @Test
    void everyPublishOfTheCorpusRoundTripsAsARecord() throws Exception {
        for (final Map.Entry<String, Packet> e : EncodeCorpus.packets().entrySet()) {
            if (e.getValue() instanceof final Packet.Publish p) {
                final byte[] record = PublishRecord.encode(p);
                assertThat(record).as(e.getKey()).isEqualTo(Codec.encode(p));
                assertThat(PublishRecord.decode(record)).as(e.getKey()).isEqualTo(p);
            }
        }
    }
}
