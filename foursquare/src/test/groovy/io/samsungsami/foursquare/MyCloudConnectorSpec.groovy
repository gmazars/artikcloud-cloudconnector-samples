package io.samsungsami.foursquare

import static java.net.HttpURLConnection.*

import utils.FakeContext
import static utils.Tools.*
import spock.lang.*
import org.scalactic.*
import scala.Option
import org.joda.time.format.DateTimeFormat
import org.joda.time.*
import cloud.artik.cloudconnector.api_v1.*
import groovy.json.JsonSlurper
import groovy.json.JsonOutput


class MyCloudConnectorSpec extends Specification {

        def sut = new MyCloudConnector()
        def parser = new JsonSlurper()
        def ctx = new FakeContext() {
            long now() { new DateTime(1970, 1, 17, 0, 0, DateTimeZone.UTC).getMillis() }
        }
        def extId = "987654321"
        def apiEndpoint = "https://api.foursquare.com/v2"
        def device = new DeviceInfo("deviceId", Option.apply(extId), new Credentials(AuthType.OAuth2, "", "abcdefg", Empty.option(), Option.apply("bearer"), [], Empty.option()), ctx.cloudId(), Empty.option())
        def allowedKeys = [
            "createdAt", 
            "timeZoneOffset", 
            "venue", "location", "lat", "lng",
            "name", "address", "city", "state", "country", "postalCode", "formattedAddress"
        ]
        def extIdKeys = [ "user", "id" ]

        def "reject Notification with invalid pushSecret"() {
            when:
            def invalidMsg = readFile(this, "apiNotificationBadSignature.json")
            def req = new RequestDef('https://foo/cloudconnector/' + extId + '/thirdpartynotification')
                    .withContent(invalidMsg, "application/json")
            def res = sut.onNotification(ctx, req)

            then:
            res.isBad()
        }

        def "reject Notification with empty checkinUserId"() {
            when:
            def invalidMsg = readFile(this, "secondCheckinEmptyUserId.json")
            def req = new RequestDef('https://foo/cloudconnector/' + extId + '/thirdpartynotification')
                    .withContent(invalidMsg, "application/json")
            def res = sut.onNotification(ctx, req)

            then:
            res.isBad()
        }

        def "test Function filterByAllowedKeys"() {
            when:
            def checkin = parser.parseText(readFile(this, "sampleCheckin.json"))[0]
            def checkinFiltered = sut.filterByAllowedKeys(checkin, allowedKeys)
            def expectedCheckinFiltered = parser.parseText(readFile(this, "expectedFilteredCheckin.json"))
            then:
            checkinFiltered == expectedCheckinFiltered
        }

        def "test Function generateNotificationsFromCheckins"() {
            when:
            def checkin = parser.parseText(readFile(this, "sampleCheckin.json"))
            def notificationGenerated = sut.generateNotificationsFromCheckins(checkin)
            def expectedNotificationGenerated = [
                new ThirdPartyNotification(new ByExternalId("1",),[],[
                    '''{"timestamp":1458147313,"timeZoneOffset":0,"venue":{"location":{"address":"568 Broadway Fl 10","city":"New York","country":"United States","formattedAddress":["568 Broadway Fl 10 (at Prince St)","New York, NY 10012"],"lat":40.72412842453194,"long":-73.99726510047911,"postalCode":"10012","state":"NY"},"name":"Foursquare HQ"}}'''
                ]), 
                new ThirdPartyNotification(new ByExternalId("987654321",),[],[
                    '''{"timestamp":1458123760,"timeZoneOffset":60,"venue":{"location":{"address":"some address","city":"Some City","country":"France","formattedAddress":["some address","75010 Some City"],"lat":108.86950328317372,"long":2.354668378829956,"postalCode":"75010","state":"Some State"},"name":"Soci\\u00e9t\\u00e9 G\\u00e9n\\u00e9rale"}}'''
                ])

            ]
            then:
            notificationGenerated[0] == expectedNotificationGenerated[0]
            notificationGenerated[1] == expectedNotificationGenerated[1]
            notificationGenerated == expectedNotificationGenerated
        }

        // renameJsonKey(obj) -< transformJson(obj, f) remove all empty values
        def "test Function renameJsonKey"() {
            when:
            def checkin = parser.parseText(readFile(this, "sampleCheckin.json"))[0]
            def checkinRenamed = sut.renameJsonKey(checkin)
            def expectedCheckinRenamed = parser.parseText(readFile(this, "expectedRenamedCheckin.json"))
            then:
            checkinRenamed == expectedCheckinRenamed
        }

        def "create data from push notification (without characters special)"() {
            when:
            def msg = readFile(this, "apiMultiNotification.json")
            def req = new RequestDef('https://foo/cloudconnector/' + extId + '/thirdpartynotification')
                    .withContent(msg, "application/json")
            def res = sut.onNotification(ctx, req)
            def expectedData= [
                    ['''{"timestamp":1458647923,"timeZoneOffset":60,"venue":{"location":{"address":"some address","city":"Some City","country":"France","formattedAddress":["some address","75010 Some City"],"lat":108.86950328317372,"long":2.354668378829956,"postalCode":"75010","state":"Ile-de-France"},"name":"Societe Generale"}}'''],
                    ['''{"timestamp":1458670144,"timeZoneOffset":60,"venue":{"location":{"country":"France","formattedAddress":["75000"],"lat":108.8542115806468,"long":2.352619171142578,"postalCode":"75000","state":"Ile-de-France"},"name":"Some City"}}''']
            ]
            def expectedResponse = new NotificationResponse( expectedData.collect { data ->
                    new ThirdPartyNotification(new ByExternalId(device.extId.get()), [], data)
            })
                    
            then:
            res.isGood()
            res.get().thirdPartyNotifications.dataProvided.size() == 2
            res.get().thirdPartyNotifications.dataProvided[0] == expectedData[0]
            res.get().thirdPartyNotifications.dataProvided[1] == expectedData[1]
            res.get() == expectedResponse
            
        }
    
        def "create events from created data"() {
            when:
            def bodyEvent_ts = 1432027166000L
            def data= [
                    '''{"timestamp":1458647923,"timeZoneOffset":60,"venue":{"location":{"address":"some address","city":"Some City","country":"France","formattedAddress":["some address","75010 Some City"],"lat":108.86950328317372,"long":2.354668378829956,"postalCode":"75010","state":"Ile-de-France"},"name":"Societe Generale"}}''',
                    '''{"timestamp":1458670144,"timeZoneOffset":60,"venue":{"location":{"country":"France","formattedAddress":["75000"],"lat":108.8542115806468,"long":2.352619171142578,"postalCode":"75000","state":"Ile-de-France"},"name":"Some City"}}''',
                    '''{"timestamp":1458147313,"timeZoneOffset":0,"venue":{"location":{"address":"568 Broadway Fl 10","city":"New York","country":"United States","formattedAddress":["568 Broadway Fl 10 (at Prince St)","New York, NY 10012"],"lat":40.72412842453194,"long":-73.99726510047911,"postalCode":"10012","state":"NY"},"name":"Foursquare HQ"}}'''
            ]
            def res = data.collectMany{ it -> sut.onNotificationData(ctx, null, it).get()}
            // timestamp *= 1000
            def expectedEvents = [
                    new Event(1458647923000,'''{"timeZoneOffset":60,"venue":{"location":{"address":"some address","city":"Some City","country":"France","formattedAddress":["some address","75010 Some City"],"lat":108.86950328317372,"long":2.354668378829956,"postalCode":"75010","state":"Ile-de-France"},"name":"Societe Generale"}}'''),
                    new Event(1458670144000,'''{"timeZoneOffset":60,"venue":{"location":{"country":"France","formattedAddress":["75000"],"lat":108.8542115806468,"long":2.352619171142578,"postalCode":"75000","state":"Ile-de-France"},"name":"Some City"}}'''),
                    new Event(1458147313000,'''{"timeZoneOffset":0,"venue":{"location":{"address":"568 Broadway Fl 10","city":"New York","country":"United States","formattedAddress":["568 Broadway Fl 10 (at Prince St)","New York, NY 10012"],"lat":40.72412842453194,"long":-73.99726510047911,"postalCode":"10012","state":"NY"},"name":"Foursquare HQ"}}''')
            ]

            then:
            res == expectedEvents
        }

}