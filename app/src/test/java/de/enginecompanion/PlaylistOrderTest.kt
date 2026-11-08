package de.enginecompanion
import de.enginecompanion.data.*
import org.junit.Assert.*
import org.junit.Test
class PlaylistOrderTest {
    @Test fun followsLinksInsteadOfRowOrder() {
        val r = PlaylistOrder.order(listOf(Membership(3,30,0,""),Membership(1,10,2,""),Membership(2,20,3,"")))
        assertEquals(listOf(10L,20L,30L),r.members.map { it.trackId });assertNull(r.warning)
    }
    @Test fun detectsCycles() { assertNotNull(PlaylistOrder.order(listOf(Membership(1,10,2,""),Membership(2,20,1,""))).warning) }
    @Test fun detectsMultipleHeads() { assertNotNull(PlaylistOrder.order(listOf(Membership(1,10,0,""),Membership(2,20,0,""))).warning) }
    @Test fun detectsDanglingPointer() { assertNotNull(PlaylistOrder.order(listOf(Membership(1,10,999,""))).warning) }
    @Test fun resolvesPlaylistEntriesByOriginIdentityAndPreservesOrder() {
        val rows = listOf(Membership(1,700,2,"source-a"), Membership(2,800,0,"source-b"))
        val tracks = listOf(TrackIdentity(42,"source-a",700), TrackIdentity(99,"source-b",800))
        assertEquals(listOf(42L,99L), MembershipResolver.resolve(rows,"local",tracks).localTrackIds)
    }
    @Test fun resolvesLocalIdsButDoesNotGuessUnknownOrigins() {
        val rows = listOf(Membership(1,10,2,"local"), Membership(2,10,0,"foreign"))
        val result = MembershipResolver.resolve(rows,"local",listOf(TrackIdentity(10,"local",10)))
        assertEquals(listOf(10L), result.localTrackIds)
        assertEquals(1, result.unresolved)
    }
    @Test fun emptyPlaylistIsValid() { assertNull(PlaylistOrder.order(emptyList()).warning) }
    @Test fun unknownVersionsAreNotGuessed() { assertNull(AdapterRegistry.resolve(DbSchema(emptyMap(),"99.0.0",null,""))) }
}
