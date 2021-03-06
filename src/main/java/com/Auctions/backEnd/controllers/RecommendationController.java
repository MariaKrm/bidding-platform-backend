package com.Auctions.backEnd.controllers;

import com.Auctions.backEnd.models.*;
import com.Auctions.backEnd.repositories.*;
import com.Auctions.backEnd.responses.Message;
import com.Auctions.backEnd.responses.RatedItem;
import info.debatty.java.lsh.LSHSuperBit;
import org.jdom.Attribute;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;
import java.util.*;

import java.io.File;
import java.io.IOException;

import org.jdom.Document;
import org.jdom.Element;
import org.jdom.JDOMException;
import org.jdom.input.SAXBuilder;

import static info.debatty.java.lsh.SuperBit.cosineSimilarity;

@RestController
@CrossOrigin(origins = "*")
@RequestMapping("/recommend")
public class RecommendationController extends BaseController{

    private final UserRepository userRepository;
    private final ItemRepository itemRepository;
    private final BidRepository bidRepository;
    private final GeolocationRepository geolocationRepository;
    private final ItemCategoryRepository itemCategoryRepository;
    private final AccountRepository accountRepository;
    private final PasswordEncoder passwordEncoder;

    @Autowired
    public RecommendationController(UserRepository userRepository, ItemRepository itemRepository,
                         BidRepository bidRepository, GeolocationRepository geolocationRepository,
                         ItemCategoryRepository itemCategoryRepository, PasswordEncoder passwordEncoder,
                                    AccountRepository accountRepository) {
        this.userRepository = userRepository;
        this.itemRepository = itemRepository;
        this.bidRepository = bidRepository;
        this.geolocationRepository = geolocationRepository;
        this.itemCategoryRepository = itemCategoryRepository;
        this.passwordEncoder = passwordEncoder;
        this.accountRepository = accountRepository;
    }


    /**
     * XML load form the Bonus data-set:
     * An admin can load all the XML items of a specified file in the database
     * The procedure is offline so the method exists only in back-end
     *
     * https://www.tutorialspoint.com/java_xml/java_jdom_parse_document.htm#
     * https://www.mkyong.com/java/how-to-read-xml-file-in-java-jdom-example/
     *
     * @return an <HTTP>OK</HTTP> when all items are loaded
     */
    @GetMapping("/xmlRead")
    public ResponseEntity loadFromXml() {

        User requester = requestUser();

        if(!requester.isAdmin()){
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(new Message(
                    "Error",
                    "You need to be an admin to perform this action"
            ));
        }

        //default location for users created by xml data
        Geolocation zero = geolocationRepository.findLocationByLatitudeAndLongitude(0.0,0.0);
        if(zero == null){
            zero = new Geolocation();
            zero.setLatitude(0.0);
            zero.setLongitude(0.0);
            zero.setLocationTitle("No available location");
            geolocationRepository.save(zero);
        }

        try {
            File inputFile = new File("ebay/items-3.xml");

            SAXBuilder saxBuilder = new SAXBuilder();
            Document document = saxBuilder.build(inputFile);

            Element classElement = document.getRootElement();
            List<Element> itemList = classElement.getChildren();

            //read all items of xml file
            for (int i = 0; i < itemList.size(); i++) {

                System.out.println("Importing " + (i+1) + " of " + itemList.size());

                Element xmlItem = itemList.get(i);
                Item item = new Item();

                item.setName(xmlItem.getChildText("Name"));

                item.setCurrently(Double.valueOf(xmlItem.getChildText("Currently")
                        .substring(1).replace(",", "")));

                item.setFirstBid(
                        Double.valueOf(xmlItem.getChildText("First_Bid")
                                .substring(1).replace(",", "")));

                if(xmlItem.getChildText("Description").length() > 255){
                    item.setDescription(xmlItem.getChildText("Description").substring(0,255));
                }
                else {
                    item.setDescription(xmlItem.getChildText("Description"));
                }

                if(xmlItem.getChildText("Buy_Price") != null){
                    item.setBuyPrice(
                            Double.valueOf(xmlItem.getChildText("Buy_Price")
                                    .substring(1).replace(",", "")));
                }

                Attribute longitude =  xmlItem.getChild("Location").getAttribute("Longitude");
                Attribute latitude =  xmlItem.getChild("Location").getAttribute("Latitude");
                String locationTitle = xmlItem.getChildText("Location") + ", " +
                        xmlItem.getChildText("Country");

                item.setEndsAt(new Date(new Date().getTime() + 10*86400000));

                //location
                Geolocation location;
                if (longitude != null && latitude != null){

                    Double lat = Double.valueOf(latitude.getValue());
                    Double lon = Double.valueOf(longitude.getValue());

                    location = geolocationRepository.findLocationByLatitudeAndLongitude(lat, lon);
                    if (location == null) {
                        location = new Geolocation(lon, lat, locationTitle);
                    }
                }
                else {
                    location = zero;
                }

                item.setLocation(location);
                location.getItems().add(item);
                geolocationRepository.save(location);

                //categories
                ItemCategory category = null;
                List<Element> categories = xmlItem.getChildren("Category");
                for (int j = 0; j < categories.size(); j++) {

                    Element cat = categories.get(j);

                    if(j == 0) {

                        category = itemCategoryRepository.findItemCategoryByName(cat.getText());
                        if (category == null) {

                            ItemCategory allCategories = itemCategoryRepository.findItemCategoryByName("All categories");

                            category = new ItemCategory();
                            category.setName(cat.getText());
                            category.setParent(allCategories);
                            itemCategoryRepository.save(category);

                            allCategories.getSubcategories().add(category);
                            itemCategoryRepository.save(allCategories);
                        }
                        item.getCategories().add(category);
                    }
                    else {

                        if (itemCategoryRepository.findItemCategoryByName(cat.getText()) == null) {

                            ItemCategory subcategory = new ItemCategory();
                            subcategory.setName(cat.getText());
                            subcategory.setParent(category);
                            itemCategoryRepository.save(subcategory);

                            category.getSubcategories().add(subcategory);
                            itemCategoryRepository.save(category);

                            item.getCategories().add(subcategory);
                            category = subcategory;
                        }
                    }
                }

                //bids
                List<Element> bids = xmlItem.getChildren("Bids");
                for (int a = 0; a < bids.size(); a++) {

                    Element BidElement = bids.get(a);
                    List<Element> bidList = BidElement.getChildren("Bid");
                    for (int b = 0; b  < bidList.size(); b++) {

                        Element bid = bidList.get(b);

                        String username = bid.getChild("Bidder").getAttribute("UserID").getValue();
                        username = username
                                .replace("@", "1")
                                .replace(".", "2")
                                .replace("$", "3")
                                .replace("*", "4")
                                .replace("(", "5")
                                .replace(")", "6");

                        User bidder = userRepository.findByAccount_Username(username);
                        if(bidder == null){
                            Account account = new Account();
                            account.setUsername(username);
                            account.setEmail(username + "@di.uoa.gr");
                            account.setPassword(passwordEncoder.encode("123456"));
                            account.setVerified(true);

                            bidder = new User();
                            bidder.setFirstName("FirstName");
                            bidder.setLastName("LastName");
                            bidder.setTelNumber("1234567890");
                            bidder.setTaxNumber("1234");
                            bidder.setAccount(account);

                            accountRepository.save(account);
                        }

                        if(bidder.getAddress() == null){
                            zero.getUsers().add(bidder);
                            bidder.setAddress(zero);
                            geolocationRepository.save(zero);
                        }

                        if(bidder.getBidderRating() == 0){
                            bidder.setBidderRating(
                                    Integer.valueOf(bid.getChild("Bidder").getAttribute("Rating").getValue()));
                        }

                        Bid newBid = new Bid();
                        newBid.setOffer(Double.valueOf(bid.getChildText("Amount").substring(1).replace(",", "")));
                        newBid.setBidder(bidder);
                        newBid.setItem(item);
                        bidRepository.save(newBid);

                        bidder.getBids().add(newBid);
                        userRepository.save(bidder);

                        item.getBids().add(newBid);
                    }
                }

                //account
                String username = xmlItem.getChild("Seller").getAttribute("UserID").getValue();
                username = username
                        .replace("@", "1")
                        .replace(".", "2")
                        .replace("$", "3")
                        .replace("*", "4")
                        .replace("(", "5")
                        .replace(")", "6");

                User seller = userRepository.findByAccount_Username(username);
                if(seller == null){
                    Account account = new Account();
                    account.setUsername(username);
                    account.setEmail(username + "@di.uoa.gr");
                    account.setPassword(passwordEncoder.encode("123456"));
                    account.setVerified(true);

                    account  = accountRepository.save(account);

                    seller = new User();
                    seller.setFirstName("FirstName");
                    seller.setLastName("LastName");
                    seller.setTelNumber("1234567890");
                    seller.setTaxNumber("1234");
                    seller.setAccount(account);
                }

                if(seller.getAddress() == null){
                    seller.setAddress(zero);
                    zero.getUsers().add(seller);
                    geolocationRepository.save(zero);
                }

                if(seller.getSellerRating() == 0){
                    seller.setSellerRating(
                            Integer.valueOf(xmlItem.getChild("Seller").getAttribute("Rating").getValue()));
                }

                item.setSeller(seller);
                userRepository.save(seller);
                seller.getItems().add(item);
                itemRepository.save(item);
            }
        } catch(JDOMException e) {
            e.printStackTrace();
        } catch(IOException ioe) {
            ioe.printStackTrace();
        }

        return ResponseEntity.ok(new Message(
                "Ok",
                "All Items have bee imported"
        ));
    }


    /**
     * We suggest the visitors to visit the 5 most bidden auctions
     *
     * @return - 5 most popular auctions
     */
    @GetMapping("/visitor")
    public ResponseEntity popularItems(){
        List<Item> items = itemRepository.popularItems();
        if(items.size() > 5){
            return ResponseEntity.ok(items.subList(0,5));
        }
        return ResponseEntity.ok(items);
    }


    /**
     * BONUS
     * A registered user can be suggested to visit some auctions
     * according to a LSH NN-CF method:
     * 1) we find all users
     * 2) we find all items
     * 3) we create the vectors
     *      - if a user has participated in an auction, we give 1.0
     *      - if a user has seen an auction without participating in, we give 0.5
     *      - else we give 0.0
     * 4) we create min( sqrt(number of users), 2) buckets
     * 5) we use the 'tdebatty' library (https://github.com/tdebatty/java-LSH) to perform LSH (with cosine)
     * 6) after LSH we get the buckets in which the activeUser belongs in and we find his neighborhood
     * 7) for every auction in which activeUser's neighbors participated, we calculate a rating prediction
     * according to the formula presented in Eclass and here:
     * https://link.springer.com/article/10.1007/s10479-016-2367-1 (part2, 2.1, step3)
     * We exclude the auctions that belong to the activeUser of those he has also participated in
     * 8) after finding the rating for every auction of the above step we sort them and return the item list
     *
     * @return - the sorted item list
     * @throws JDOMException - lsh algorithm
     * @throws IOException- lsh algorithm
     */
    @GetMapping("/lsh")
    public ResponseEntity lsh() throws JDOMException, IOException {

        String username = requestUser().getUsername();

        //1) get all users
        List<User> allUsers = userRepository.findAll();
        int userSize = allUsers.size();

        if(userSize == 0 || userSize == 1){
            return ResponseEntity.ok(null);
        }

        //2) get all items
        List<Item> allItems = itemRepository.findAll();
        int itemSize = allItems.size();

        if(itemSize == 0){
            return ResponseEntity.ok(null);
        }

        //3) create vectors of 'userSize' users * 'itemSize' items
        double[][] vectors = new double[userSize][];
        for (int i = 0; i < userSize; i++) {
            vectors[i] = new double[itemSize];

            //auctions the user 'i' has participated in
            List<Item> items = new ArrayList<>();
            allUsers.get(i).getBids().forEach(bid -> {
                items.add(bid.getItem());
            });

            for (int j = 0; j < itemSize; j++) {
                if(items.contains(allItems.get(j))) {
                    vectors[i][j] = 1.0;
                } else if(allUsers.get(i).getItemSeen().contains(allItems.get(j))){
                    vectors[i][j] = 0.5;
                } else {
                    vectors[i][j] = 0.0;
                }
            }
        }


        //4)stages and buckets and initialization
        int stages = 3;
        int buckets = (int) (Math.sqrt(userSize) > 2  ? Math.sqrt(userSize) : 2);

        int activeUserBucket = -1;
        int activeUserPosition = 0;
        double avgRating = 0.0;

        LSHSuperBit lsh = new LSHSuperBit(stages, buckets, itemSize);
        Map<Integer, List<Integer>> map = new HashMap<>();

        //5) LSH
        for (int i = 0; i < userSize; i++) {

            double[] vector = vectors[i];
            int[] hash = lsh.hash(vector);

            List<Integer> neighbours = map.get(hash[0]);
            if(neighbours == null){
                neighbours = new ArrayList<>();
            }
            neighbours.add(i);
            map.put(hash[0], neighbours);

            if(allUsers.get(i).getUsername().equals(username)){
                activeUserBucket = hash[0];
                activeUserPosition = i;
                avgRating =  Arrays.stream(vector).average().orElse(0);
            }
        }

        //6) after LSH we get the bucket in which the activeUser belongs in
        List<Integer> neighborhood = map.get(activeUserBucket);
        if(neighborhood == null){
            return ResponseEntity.ok(null);
        }

        neighborhood.removeIf(number -> allUsers.get(number).getUsername().equals(username));

        int finalActiveUserPosition = activeUserPosition;

        //lambda - normalizing factor
        double lambda = 1 / neighborhood.stream().mapToDouble(
                neighbourPosition -> cosineSimilarity(vectors[finalActiveUserPosition], vectors[neighbourPosition])).sum();

        //auctions that activeUser has participated in
        List<Item> participations = new ArrayList<>();
        allUsers.get(finalActiveUserPosition).getBids().forEach(bid -> {
            participations.add(bid.getItem());
        });

        //7) for every neighbour of the activeUser we get the auctions they have participated in
        List<RatedItem> ratedItems = new ArrayList<>();
        double finalAvgRating = avgRating;
        neighborhood.forEach(neighbourPosition -> {
            allUsers.get(neighbourPosition).getBids().forEach(bid -> {

                //we process the auction olny if it does not belong to the activeUser or he has already bidden
                if(!participations.contains(bid.getItem()) &&
                        !allUsers.get(finalActiveUserPosition).getItems().contains(bid.getItem())) {

                    double sum = neighborhood.stream().mapToDouble(neighbourPos ->
                            cosineSimilarity(vectors[finalActiveUserPosition], vectors[neighbourPos]) *
                                    (1 - Arrays.stream(vectors[neighbourPos]).average().orElse(0))
                    ).sum();

                    List<Item> existingRatings = new ArrayList<>();
                    ratedItems.forEach(item -> existingRatings.add(item.getItem()));
                    if(!existingRatings.contains(bid.getItem())){
                        ratedItems.add(new RatedItem(bid.getItem(), finalAvgRating + lambda * sum));
                    }
                    else{
                        double score = finalAvgRating + lambda * sum;
                        if(ratedItems.get(existingRatings.indexOf(bid.getItem())).getRating() < score){
                            ratedItems.get(existingRatings.indexOf(bid.getItem())).setRating(score);
                        }
                    }
                }
            });
        });

        //8) sorting according to calculated rating..
        ratedItems.sort(Comparator.comparingDouble(RatedItem::getRating).reversed());

        //..and get the recommended items
        List<Item> finalRatings = new ArrayList<>();
        int size = ratedItems.size() > 5 ? 5 : ratedItems.size();

        for(int k = 0; k < size; k++){
            finalRatings.add(ratedItems.get(k).getItem());
        }

        return ResponseEntity.ok(finalRatings);
    }
}

