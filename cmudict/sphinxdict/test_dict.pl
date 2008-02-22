#!/usr/bin/perl -w
# do a sanity check on a dictionary (# of tabs, duplicates)
# [21oct98] (air) created
# [30oct98] (air) expanded functionality: check for phonetic symbols
# [03feb99] (air) bug fix; added noise symbols to check
# [20010623] (air) added cmd-line flags; word/pron bound now \s+

#
# correct dictionary format is:   ^WORD\tW ER DD\n$
# 
# - "W ER DD" are symbols from the legal phone set(s)
# - no leading/trailing spaces allowed
# - no duplicates words allowed
# - character collating sequence enforced
# 
# above spec should cover all (current) consumers of the dictionary file.
# not all conventions checked however (eg, for multiple pronunciations)
#

use Getopt::Std; use vars qw/ $opt_p $opt_n /;
if ($#ARGV<0) { die("usage: test_dict -p <phonefile> [-n <noisefile>] <dictfile>\n"); }
getopt('p:n:'); $phonefile = $opt_p; $noisefile = $opt_n;
$dictfile = $ARGV[0];

# get the legal symbol set
open(PH,$phonefile) || die("can't open $phonefile!\n");
while (<PH>) { chomp; $phone{$_} = 1; } close(PH);
open(PH,$noisefile) || die("can't open $noisefile!\n");
while (<PH>) { chomp; $phone{$_} = 1; } close(PH);
open(DICT,$dictfile) ||die("$dictfile not found!\n");

# go through dict, do tests
%dict = (); $last = ""; my ($lead, $trail); $word_cnt = 0;
while (<DICT>) {
    chomp;  #    s/^\s*(.+?)\s*$/$1/;
    $line = $_;
    ($word,$pron) = split (/\s+/,$line,2);  # BIG assumption about no leading junk...
    $dict{$word}++;

    ($lead = $_) =~ s/^\s*(.+)/$1/;
    ($trail = $_) =~ s/(.+?)\s*$/$1/;
    if ($line ne $trail) { print "ERROR: trailing space in '$line'!\n"; }
    if ($line ne $lead) { print "ERROR: leading space in '$line'!\n"; }
    if ( $last ge $word ) { print "ERROR: collation sequence for $last, $word wrong\n"; }

    # check for legal symbols
    @sym = split(/\s/,$pron);
    $errs = "";
    foreach $s (@sym) {	if ( ! $phone{$s} ) { $errs .= " $s"; } else { $phone{$s}++; } }
    if ($errs ne "") { print "ERROR: $word has illegal symbols: '$errs'\n"; }

    # bad format
    @line = split (/\t/,$line);
    if ( $#line != 1 ) { print "WARNING: tabbing error (",$#line, ") in: $line\n"; }
    $word_cnt++;
    $last = $word;
}
close(DICT);

# check for duplicates entries
foreach $x (keys %dict) {
    if ($dict{$x}>1) { print "ERROR: $x occurs ", $dict{$x}, " times!\n"; }
}

print STDERR "processed $word_cnt words.\n";

# print out the phone counts
# foreach (sort keys %phone) { print STDERR "$_\t$phone{$_}\n"; }

#