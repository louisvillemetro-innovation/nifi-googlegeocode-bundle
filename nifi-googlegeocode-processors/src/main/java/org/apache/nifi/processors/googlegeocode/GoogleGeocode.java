package org.apache.nifi.processors.googlegeocode;

/**
 * Created by dpinkston on 6/24/16.
 */

import com.google.maps.GeoApiContext;
import com.google.maps.GeocodingApi;
import com.google.maps.model.GeocodingResult;
import org.apache.commons.io.IOUtils;
import org.apache.nifi.annotation.behavior.*;
import org.apache.nifi.annotation.behavior.InputRequirement.Requirement;
import org.apache.nifi.annotation.documentation.CapabilityDescription;
import org.apache.nifi.annotation.documentation.Tags;
import org.apache.nifi.annotation.lifecycle.OnStopped;
import org.apache.nifi.components.PropertyDescriptor;
import org.apache.nifi.flowfile.FlowFile;
import org.apache.nifi.processor.*;
import org.apache.nifi.processor.exception.ProcessException;
import org.apache.nifi.processor.io.InputStreamCallback;
import org.apache.nifi.processor.io.OutputStreamCallback;
import org.apache.nifi.processor.util.StandardValidators;
import org.apache.nifi.processors.googlegeocode.util.GoogleGeocodeURLBuilder;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.*;


@EventDriven
@SideEffectFree
@SupportsBatching
@Tags({"coordinates", "enrich", "address", "geocode"})
@InputRequirement(Requirement.INPUT_REQUIRED)
@CapabilityDescription("Looks up coordinates via google geocode service")
@WritesAttributes({
        @WritesAttribute(attribute = "X.location_1.coordinates", description = "Coordinates based off the provided address"),
        @WritesAttribute(attribute = "X.location_1.type", description = "Point type of geocode"),})
public class GoogleGeocode extends AbstractProcessor {

    public static final PropertyDescriptor GOOGLE_GEOCODE_API_KEY = new PropertyDescriptor.Builder()
            .name("Google Geocode API Key")
            .description("Provide a Google Geocode server API key, one can be created here: https://developers.google.com/maps/documentation/geocoding/start#get-a-key")
            .required(true)
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .sensitive(true)
            .build();
    public static final PropertyDescriptor ADDRESS_ATTRIBUTE = new PropertyDescriptor.Builder()
            .name("Street Address Attribute")
            .required(true)
            .description("The name of an attribute whose value is a street address for which enrichment should occur")
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .build();
    public static final PropertyDescriptor CITY_ATTRIBUTE = new PropertyDescriptor.Builder()
            .name("City Attribute")
            .required(true)
            .description("The name of an attribute whose value is a city for which enrichment should occur")
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .build();
    public static final PropertyDescriptor STATE_ATTRIBUTE = new PropertyDescriptor.Builder()
            .name("State Attribute")
            .required(true)
            .description("The name of an attribute whose value is a State for which enrichment should occur")
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .build();

    public static final Relationship REL_FOUND = new Relationship.Builder()
            .name("found")
            .description("Where to route flow files after successfully enriching attributes with geo data")
            .build();

    public static final Relationship REL_NOT_FOUND = new Relationship.Builder()
            .name("not found")
            .description("Where to route flow files after unsuccessfully enriching attributes because no geo data was found")
            .build();

    private Set<Relationship> relationships;
    private List<PropertyDescriptor> propertyDescriptors;


    @Override
    public Set<Relationship> getRelationships() {
        return relationships;
    }

    @Override
    protected List<PropertyDescriptor> getSupportedPropertyDescriptors() {
        return propertyDescriptors;
    }

    @OnStopped
    public void closeService() throws IOException {
        //TODO
    }

    @Override
    protected void init(final ProcessorInitializationContext context) {
        final Set<Relationship> rels = new HashSet<>();
        rels.add(REL_FOUND);
        rels.add(REL_NOT_FOUND);
        this.relationships = Collections.unmodifiableSet(rels);

        final List<PropertyDescriptor> props = new ArrayList<>();
        props.add(GOOGLE_GEOCODE_API_KEY);
        props.add(ADDRESS_ATTRIBUTE);
        props.add(CITY_ATTRIBUTE);
        props.add(STATE_ATTRIBUTE);
        this.propertyDescriptors = Collections.unmodifiableList(props);
    }

    @Override
    public void onTrigger(final ProcessContext context, final ProcessSession session) throws ProcessException {
        FlowFile flowFile = session.get();
        final JSONObject[] json = {new JSONObject()};
        if (flowFile == null) {
            return;
        }

        final String addressAttributeName = context.getProperty(ADDRESS_ATTRIBUTE).getValue();
        final String addressAttributeValue = flowFile.getAttribute(addressAttributeName);
        final String cityAttributeName = context.getProperty(CITY_ATTRIBUTE).getValue();
        final String cityAttributeValue = flowFile.getAttribute(cityAttributeName);
        final String stateAttributeName = context.getProperty(STATE_ATTRIBUTE).getValue();
        final String stateAttributeValue = flowFile.getAttribute(stateAttributeName);
        final String apiKeyAttributeValue = context.getProperty(GOOGLE_GEOCODE_API_KEY).getValue();

        GoogleGeocodeURLBuilder urlBuilder = new GoogleGeocodeURLBuilder(addressAttributeValue, cityAttributeValue,
                stateAttributeValue, apiKeyAttributeValue);

        GeoApiContext geoApiContext = new GeoApiContext().setApiKey(apiKeyAttributeValue);
        GeocodingResult[] results;
        try {
            results = GeocodingApi.geocode(geoApiContext, urlBuilder.getFormattedAddress()).await();
        } catch (Exception e) {
            e.printStackTrace();
            session.transfer(flowFile, REL_NOT_FOUND);
            getLogger().warn("Failure while trying to find coordinates for {} due to {}", new Object[]{flowFile, e}, e);
            return;
        }

        //geocodeResponse.getStatus error routing/relationships
        if (results == null) {
            session.transfer(flowFile, REL_NOT_FOUND);
            return;
        }

        final GeocodingResult[] finalResults = results;
        session.read(flowFile, new InputStreamCallback() {
            @Override
            public void process(InputStream in) throws IOException {
                try {
                    String inputJson = IOUtils.toString(in);
                    json[0] = new JSONObject(inputJson);
                    JSONObject locationType = new JSONObject();
                    locationType.put("type", "Point");
                    JSONArray coordinates = new JSONArray();
                    coordinates.put(finalResults[0].geometry.location);
                    locationType.put("coordinates: ", coordinates);
                    json[0].put("location_1", locationType);
                } catch (Exception ex) {
                    ex.printStackTrace();
                    getLogger().error("Failed to read json string.");
                }
            }
        });

        // To write the results back out ot flow file
        flowFile = session.write(flowFile, new OutputStreamCallback() {

            @Override
            public void process(OutputStream out) throws IOException {
                out.write(json[0].toString().getBytes());
            }
        });

        session.transfer(flowFile, REL_FOUND);
    }
}

