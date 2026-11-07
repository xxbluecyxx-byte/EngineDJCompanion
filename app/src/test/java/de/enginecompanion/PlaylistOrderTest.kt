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
    @Test fun foreignAndUnknownDatabaseIdsAreNotJoinedLocally() {
        val rows = listOf(Membership(1,10,2,"local"),Membership(2,10,3,"foreign"),Membership(3,20,0,""))
        assertEquals(listOf(1L),MembershipResolver.local(rows,"local").map { it.id });assertTrue(MembershipResolver.local(rows,null).isEmpty())
    }
    @Test fun emptyPlaylistIsValid() { assertNull(PlaylistOrder.order(emptyList()).warning) }
    @Test fun unknownVersionsAreNotGuessed() { assertNull(AdapterRegistry.resolve(DbSchema(emptyMap(),"99.0.0",null,""))) }
}
