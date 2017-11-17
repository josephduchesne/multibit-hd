# MultiBit-HD extract private keys modification fork
## This lets you get BitCoin Cash from your MultiBitHD wallet

For the original documentation (how to use, why it's abandoned, how to compile) see the original readme in the original repo (forked from... above in github).

This repo has a one-line modification to allow you to extract the private keys from your wallet and import them into another wallet.

You do **not** need your wallet words for this, just your password.

If you do have your wallet words, your life is much easier. Follow this video: https://www.youtube.com/watch?v=E-KcY6KUVnY

## How to extract your keys

### Preparation and opening
1. First, *carefully* move all Bitcoin to a different wallet. You don't want them in a Multibit-HD wallet anyway, and if someone malicious forks this and tampers with the pre-built binary they could easily "phone home" with your keys and steal any bitcoins you have in the wallet. That said, they'd probably delete this section unless they were really dumb.
2. Install JRE 1.8 http://www.oracle.com/technetwork/java/javase/downloads/jre8-downloads-2133155.html
4. Download the modified MultiBitHD jar file (or compile from this repo using the origin repo's instructions). Precompiled jar here: https://github.com/josephduchesne/multibit-hd/raw/develop/mbhd-swing/target/multibit-hd.jar
5. Open "cmd" (windows command prompt, or Terminal on mac/linux)
6. Drag in the jre1.8 java.exe (mine was located in "C:\Program Files\Java\jre1.8.0_151\bin\java.exe"), add " -jar " and drag in the "multibit-hd-mod.jar" into your cmd prompt (final output should look something like: "C:\Program Files\Java\jdk1.8.0_151\bin\java.exe" -jar C:\Users\your_user\Downloads\multibit-hd.jar )
7. Press enter to open the modified multibit hd
8. open your wallet / enter your wallet password

### Extract wallet addresses
1. Find your bitcoin addresses using the Payments window. 
2. If your payments window doesn't contain all of your addresses, you can export using the "Export" button
3. open the resulting "transactions-DATE.csv" file, and read the RECIEVED transaction name from the *last* column and use https://blockchain.info to extract the desination wallet address
..* For example, I have a line ending with "0873b33253886495dee232e35817a76f0dcbed6968d713df083c96f21776c2ce".
..* Search for that transaction here: https://blockchain.info
..* Grab the destination address to the right of the green arrow (in my example it's 1EYF2RDacFPrnWLKHoUuk4txjYXx9Kaq2D )
4. Store each destination address that you used to recieve bitcoin in a document temporarily
  
### Extract wallet private keys
1. Click Tools -> Sign message
2. Paste in your first wallet address into "Bitcoin address", anything you want into "Message", and your wallet password into "Enter password"
3. Click "Sign Message"
4. Look at the command prompt/terminal and watch for "EXTRACTED private key: YOUR KEY HERE"
5. hit ctrl-m to enter mark mode and select the key and right click to copy, then paste it into notepad or a similar app
6. Repeat this section for each of your other keys

### Import your wallet private keys
1. Open electron cash (get it here: Install electron cash https://electroncash.org/ )
2. Create a new standard wallet
3. "use public of private keys"
4. paste your private keys found in the previous section into the text box
5. hit next
6. You should see your transaction history of the incoming coins, and your positive balance.
7. You now have recovered your BitCoin Cash

If you find guide this useful, feel free to fire any tips my way:
- Bitcoin **cash**: 1LA8Mx5vNMabHE9pT9iSL1kckBHcZ8Fysh
- Bitcoin: 1NXZCF6uR1SrwYH7TMR4PMsXU6NXX2K3Sc
